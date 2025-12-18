package ru.tinkoff.invest.emulator.grpc.service;

import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import ru.tinkoff.invest.emulator.config.EmulatorProperties;
import ru.tinkoff.invest.emulator.core.event.OrderBookChangedEvent;
import ru.tinkoff.invest.emulator.core.orderbook.OrderBookManager;
import ru.tinkoff.invest.emulator.core.stream.StreamManager;
import ru.tinkoff.invest.emulator.grpc.mapper.GrpcMapper;
import ru.tinkoff.piapi.contract.v1.*;
import ru.tinkoff.piapi.contract.v1.MarketDataStreamServiceGrpc.MarketDataStreamServiceImplBase;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@GrpcService
@RequiredArgsConstructor
public class MarketDataStreamServiceImpl extends MarketDataStreamServiceImplBase {

    private final StreamManager streamManager;
    private final OrderBookManager orderBookManager;
    private final EmulatorProperties properties;

    @Override
    public StreamObserver<MarketDataRequest> marketDataStream(StreamObserver<MarketDataResponse> responseObserver) {
        log.info("GRPC MarketDataStream: New bidirectional stream established");

        return new StreamObserver<>() {
            @Override
            public void onNext(MarketDataRequest request) {
                if (request.hasSubscribeOrderBookRequest()) {
                    SubscribeOrderBookRequest subReq = request.getSubscribeOrderBookRequest();
                    log.debug("GRPC MarketDataStream: OrderBook subscription request, action={}, instruments={}",
                            subReq.getSubscriptionAction(), subReq.getInstrumentsList().size());

                    if (subReq.getSubscriptionAction() == SubscriptionAction.SUBSCRIPTION_ACTION_SUBSCRIBE) {
                        for (OrderBookInstrument instr : subReq.getInstrumentsList()) {
                            log.info("GRPC MarketDataStream: Subscribing to OrderBook for instrument={}, depth={}",
                                    instr.getInstrumentId(), instr.getDepth());
                            streamManager.addOrderBookSubscription(responseObserver, instr.getInstrumentId());

                            // Confirm subscription
                            responseObserver.onNext(MarketDataResponse.newBuilder()
                                    .setSubscribeOrderBookResponse(SubscribeOrderBookResponse.newBuilder()
                                            .setTrackingId("track-" + System.currentTimeMillis())
                                            .addOrderBookSubscriptions(OrderBookSubscription.newBuilder()
                                                    .setFigi(properties.getInstrument().getFigi())
                                                    .setDepth(instr.getDepth())
                                                    .setSubscriptionStatus(SubscriptionStatus.SUBSCRIPTION_STATUS_SUCCESS)
                                                    .setInstrumentUid(instr.getInstrumentId())
                                                    .setStreamId("stream-" + System.currentTimeMillis())
                                                    .setSubscriptionId(java.util.UUID.randomUUID().toString())
                                                    .setOrderBookType(OrderBookType.ORDERBOOK_TYPE_EXCHANGE)
                                                    .setSubscriptionAction(SubscriptionAction.SUBSCRIPTION_ACTION_SUBSCRIBE)
                                                    .setTicker(properties.getInstrument().getTicker())
                                                    .setClassCode("TQBR")
                                                    .build())
                                            .build())
                                    .build());
                        }
                    } else {
                        log.info("GRPC MarketDataStream: Unsubscribe request received");
                        streamManager.removeSubscription(responseObserver);
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                log.warn("GRPC MarketDataStream: Stream error: {}", t.getMessage());
                streamManager.removeSubscription(responseObserver);
            }

            @Override
            public void onCompleted() {
                log.info("GRPC MarketDataStream: Client closed stream");
                streamManager.removeSubscription(responseObserver);
                responseObserver.onCompleted();
            }
        };
    }

    @EventListener
    public void onOrderBookChanged(OrderBookChangedEvent event) {
        ru.tinkoff.invest.emulator.core.model.OrderBook coreBook = event.getOrderBook();

        log.debug("GRPC MarketDataStream: Broadcasting OrderBook update for instrument={}, bids={}, asks={}",
                coreBook.getInstrumentId(), coreBook.getBids().size(), coreBook.getAsks().size());

        // Рассчитаем лимиты цен (±10% от текущей цены)
        BigDecimal bestBid = orderBookManager.getBestBid();
        BigDecimal bestAsk = orderBookManager.getBestAsk();
        BigDecimal midPrice = bestBid != null && bestAsk != null
                ? bestBid.add(bestAsk).divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP)
                : BigDecimal.valueOf(7.70);
        BigDecimal limitUp = midPrice.multiply(BigDecimal.valueOf(1.10));
        BigDecimal limitDown = midPrice.multiply(BigDecimal.valueOf(0.90));

        OrderBook ob = OrderBook.newBuilder()
                .setFigi(properties.getInstrument().getFigi())
                .setDepth(50)
                .setIsConsistent(true)
                .setTime(GrpcMapper.toTimestamp(Instant.now()))
                .addAllBids(mapOrders(coreBook.getBids().values()))
                .addAllAsks(mapOrders(coreBook.getAsks().values()))
                .setInstrumentUid(coreBook.getInstrumentId())
                .setLimitUp(GrpcMapper.toQuotation(limitUp))
                .setLimitDown(GrpcMapper.toQuotation(limitDown))
                .setOrderBookType(OrderBookType.ORDERBOOK_TYPE_EXCHANGE)
                .setTicker(properties.getInstrument().getTicker())
                .setClassCode("TQBR")
                .build();

        MarketDataResponse response = MarketDataResponse.newBuilder()
                .setOrderbook(ob)
                .build();

        streamManager.broadcastOrderBook(coreBook.getInstrumentId(), response);
    }
    
    private List<Order> mapOrders(Iterable<ru.tinkoff.invest.emulator.core.model.PriceLevel> levels) {
        List<Order> result = new java.util.ArrayList<>();
        for (ru.tinkoff.invest.emulator.core.model.PriceLevel level : levels) {
             result.add(Order.newBuilder()
                     .setPrice(GrpcMapper.toQuotation(level.getPrice()))
                     .setQuantity(level.getTotalQuantity())
                     .build());
        }
        return result;
    }

    /**
     * Периодическая отправка стакана для поддержания активности стрима.
     * SDK T-Invest отменяет стрим при отсутствии данных ~15 секунд.
     */
    @Scheduled(fixedRate = 5000)
    public void sendPeriodicOrderBook() {
        ru.tinkoff.invest.emulator.core.model.OrderBook coreBook = orderBookManager.getSnapshot(50);
        if (coreBook == null) {
            log.debug("sendPeriodicOrderBook: snapshot is null");
            return;
        }
        log.debug("sendPeriodicOrderBook: broadcasting for instrumentId={}, bids={}, asks={}",
                coreBook.getInstrumentId(), coreBook.getBids().size(), coreBook.getAsks().size());

        // Рассчитаем лимиты цен (±10% от текущей цены)
        BigDecimal bestBid = orderBookManager.getBestBid();
        BigDecimal bestAsk = orderBookManager.getBestAsk();
        BigDecimal midPrice = bestBid != null && bestAsk != null
                ? bestBid.add(bestAsk).divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP)
                : BigDecimal.valueOf(7.70);
        BigDecimal limitUp = midPrice.multiply(BigDecimal.valueOf(1.10));
        BigDecimal limitDown = midPrice.multiply(BigDecimal.valueOf(0.90));

        OrderBook ob = OrderBook.newBuilder()
                .setFigi(properties.getInstrument().getFigi())
                .setDepth(50)
                .setIsConsistent(true)
                .setTime(GrpcMapper.toTimestamp(Instant.now()))
                .addAllBids(mapOrders(coreBook.getBids().values()))
                .addAllAsks(mapOrders(coreBook.getAsks().values()))
                .setInstrumentUid(coreBook.getInstrumentId())
                .setLimitUp(GrpcMapper.toQuotation(limitUp))
                .setLimitDown(GrpcMapper.toQuotation(limitDown))
                .setOrderBookType(OrderBookType.ORDERBOOK_TYPE_EXCHANGE)
                .setTicker(properties.getInstrument().getTicker())
                .setClassCode("TQBR")
                .build();

        MarketDataResponse response = MarketDataResponse.newBuilder()
                .setOrderbook(ob)
                .build();

        streamManager.broadcastOrderBook(coreBook.getInstrumentId(), response);
    }
}
