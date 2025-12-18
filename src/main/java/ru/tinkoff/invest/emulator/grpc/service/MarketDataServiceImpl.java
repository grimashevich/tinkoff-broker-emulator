package ru.tinkoff.invest.emulator.grpc.service;

import com.google.protobuf.Timestamp;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import ru.tinkoff.invest.emulator.core.orderbook.OrderBookManager;
import ru.tinkoff.invest.emulator.grpc.mapper.GrpcMapper;
import ru.tinkoff.piapi.contract.v1.*;
import ru.tinkoff.piapi.contract.v1.MarketDataServiceGrpc.MarketDataServiceImplBase;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@GrpcService
@RequiredArgsConstructor
public class MarketDataServiceImpl extends MarketDataServiceImplBase {

    private final OrderBookManager orderBookManager;

    @Override
    public void getOrderBook(GetOrderBookRequest request, StreamObserver<GetOrderBookResponse> responseObserver) {
        log.info("GRPC GetOrderBook: instrumentId={}, depth={}", request.getInstrumentId(), request.getDepth());

        ru.tinkoff.invest.emulator.core.model.OrderBook coreBook = orderBookManager.getSnapshot(request.getDepth());

        log.debug("GRPC GetOrderBook: Returning {} bid levels, {} ask levels",
                coreBook.getBids().size(), coreBook.getAsks().size());

        GetOrderBookResponse response = GetOrderBookResponse.newBuilder()
                .setFigi(request.getInstrumentId()) // Echo back
                .setDepth(request.getDepth())
                .addAllBids(mapOrders(coreBook.getBids().values()))
                .addAllAsks(mapOrders(coreBook.getAsks().values()))
                .setInstrumentUid(request.getInstrumentId())
                .setOrderbookTs(GrpcMapper.toTimestamp(Instant.now()))
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void getTradingStatus(GetTradingStatusRequest request, StreamObserver<GetTradingStatusResponse> responseObserver) {
        String instrumentId = request.hasInstrumentId() ? request.getInstrumentId() : request.getFigi();
        log.info("GRPC GetTradingStatus: instrumentId={}", instrumentId);

        GetTradingStatusResponse response = GetTradingStatusResponse.newBuilder()
                .setFigi(instrumentId)
                .setInstrumentUid(instrumentId)
                .setTradingStatus(SecurityTradingStatus.SECURITY_TRADING_STATUS_NORMAL_TRADING)
                .setLimitOrderAvailableFlag(true)
                .setMarketOrderAvailableFlag(true)
                .setApiTradeAvailableFlag(true)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private List<Order> mapOrders(Iterable<ru.tinkoff.invest.emulator.core.model.PriceLevel> levels) {
        // Core PriceLevel contains List<Order>.
        // API Order is just Price + Quantity.
        // We need to aggregate quantity at each level?
        // My PriceLevel has `getTotalQuantity`.

        List<Order> result = new java.util.ArrayList<>();
        for (ru.tinkoff.invest.emulator.core.model.PriceLevel level : levels) {
             result.add(Order.newBuilder()
                     .setPrice(GrpcMapper.toQuotation(level.getPrice()))
                     .setQuantity(level.getTotalQuantity())
                     .build());
        }
        return result;
    }
}
