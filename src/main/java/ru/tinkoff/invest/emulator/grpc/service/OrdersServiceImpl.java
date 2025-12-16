package ru.tinkoff.invest.emulator.grpc.service;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.context.ApplicationEventPublisher;
import ru.tinkoff.invest.emulator.core.event.OrderStateChangedEvent;
import ru.tinkoff.invest.emulator.core.matching.ProRataMatchingEngine;
import ru.tinkoff.invest.emulator.core.model.Order;
import ru.tinkoff.invest.emulator.core.model.OrderDirection;
import ru.tinkoff.invest.emulator.core.model.OrderSource;
import ru.tinkoff.invest.emulator.core.model.OrderType;
import ru.tinkoff.invest.emulator.core.model.Trade;
import ru.tinkoff.invest.emulator.core.orderbook.OrderBookManager;
import ru.tinkoff.invest.emulator.core.state.AccountManager;
import ru.tinkoff.invest.emulator.grpc.mapper.GrpcMapper;
import ru.tinkoff.piapi.contract.v1.*;
import ru.tinkoff.piapi.contract.v1.OrdersServiceGrpc.OrdersServiceImplBase;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Slf4j
@GrpcService
@RequiredArgsConstructor
public class OrdersServiceImpl extends OrdersServiceImplBase {

    private final OrderBookManager orderBookManager;
    private final ProRataMatchingEngine matchingEngine;
    private final AccountManager accountManager;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void postOrder(PostOrderRequest request, StreamObserver<PostOrderResponse> responseObserver) {
        log.info("PostOrder: {}", request);
        
        try {
            // ... (validation)
            if (request.getQuantity() <= 0) {
                throw Status.INVALID_ARGUMENT.withDescription("Quantity must be positive").asRuntimeException();
            }

            OrderDirection dir = mapDirection(request.getDirection());
            OrderType type = mapType(request.getOrderType());
            BigDecimal price = GrpcMapper.toBigDecimal(request.getPrice());

            UUID orderId = request.getOrderId() != null && !request.getOrderId().isEmpty() 
                    ? UUID.fromString(request.getOrderId()) 
                    : UUID.randomUUID();

            Order order = Order.builder()
                    .id(orderId)
                    .accountId(request.getAccountId())
                    .instrumentId(request.getInstrumentId())
                    .direction(dir)
                    .type(type)
                    .price(price)
                    .quantity(request.getQuantity())
                    .source(OrderSource.API)
                    .build();
            
            // Publish initial state NEW
            eventPublisher.publishEvent(new OrderStateChangedEvent(this, order));
            
            List<Trade> trades = matchingEngine.executeOrder(order);
            
            // ... (rest of method)
            
            // Update Account based on trades
            for (Trade trade : trades) {
                 // Update account for Aggressor (current order)
                 // Assumption: Aggressor is always API/Bot order in this method (PostOrder)
                 boolean isAggressorBuy = order.getDirection() == OrderDirection.BUY;
                 accountManager.updateState(trade.getInstrumentId(), trade.getQuantity(), trade.getPrice(), isAggressorBuy);
                 
                 // Update account for Passive order if it belongs to the Bot (API)
                 if (trade.getPassiveOrderSource() == OrderSource.API) {
                     boolean isPassiveBuy = !isAggressorBuy;
                     accountManager.updateState(trade.getInstrumentId(), trade.getQuantity(), trade.getPrice(), isPassiveBuy);
                 }
            }
            
            // If Limit and not fully filled, add remainder to book
            if (order.getType() == OrderType.LIMIT && !order.isFullyFilled()) {
                orderBookManager.addOrder(order);
            }

            // Construct Response
            PostOrderResponse response = PostOrderResponse.newBuilder()
                    .setOrderId(order.getId().toString())
                    .setExecutionReportStatus(mapStatus(order.getStatus()))
                    .setLotsRequested(request.getQuantity())
                    .setLotsExecuted(order.getFilledQuantity())
                    .setInitialOrderPrice(GrpcMapper.toMoneyValue(price.multiply(BigDecimal.valueOf(request.getQuantity())), "RUB")) // TODO currency
                    .setExecutedOrderPrice(GrpcMapper.toMoneyValue(calculateExecutedValue(trades), "RUB"))
                    .setDirection(request.getDirection())
                    .setOrderType(request.getOrderType())
                    .setFigi(request.getInstrumentId())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("PostOrder failed", e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void cancelOrder(CancelOrderRequest request, StreamObserver<CancelOrderResponse> responseObserver) {
        log.info("CancelOrder: {}", request);
        UUID orderId = UUID.fromString(request.getOrderId());
        
        // We should verify accountId matches order owner.
        Order order = orderBookManager.getOrder(orderId);
        if (order != null && !order.getAccountId().equals(request.getAccountId())) {
             responseObserver.onError(Status.PERMISSION_DENIED.withDescription("Order belongs to another account").asRuntimeException());
             return;
        }

        boolean removed = orderBookManager.removeOrder(orderId);
        if (removed) {
             responseObserver.onNext(CancelOrderResponse.newBuilder()
                     .setTime(GrpcMapper.toTimestamp(java.time.Instant.now()))
                     .build());
             responseObserver.onCompleted();
        } else {
             responseObserver.onError(Status.NOT_FOUND.withDescription("Order not found").asRuntimeException());
        }
    }

    @Override
    public void getOrders(GetOrdersRequest request, StreamObserver<GetOrdersResponse> responseObserver) {
        List<Order> orders = orderBookManager.getOrders(request.getAccountId());
        
        GetOrdersResponse.Builder builder = GetOrdersResponse.newBuilder();
        for (Order o : orders) {
            builder.addOrders(OrderState.newBuilder()
                    .setOrderId(o.getId().toString())
                    .setLotsRequested(o.getQuantity())
                    .setLotsExecuted(o.getFilledQuantity())
                    .setExecutionReportStatus(mapStatus(o.getStatus()))
                    .setFigi(o.getInstrumentId())
                    .setDirection(mapDirectionProto(o.getDirection()))
                    .setOrderType(mapTypeProto(o.getType()))
                    .setInitialOrderPrice(GrpcMapper.toMoneyValue(o.getPrice().multiply(BigDecimal.valueOf(o.getQuantity())), "RUB"))
                    .build());
        }
        
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void getMaxLots(GetMaxLotsRequest request, StreamObserver<GetMaxLotsResponse> responseObserver) {
        BigDecimal price = request.hasPrice() ? GrpcMapper.toBigDecimal(request.getPrice()) : null;
        if (price == null || price.compareTo(BigDecimal.ZERO) == 0) {
            // Use current market price if not provided
             // For Buy: use Best Ask (lowest sell price)
             // For Sell: use Best Bid (highest buy price)
             // If book empty?
             price = orderBookManager.getBestAsk(); // Default to Ask for conservative
             if (price == null) price = orderBookManager.getBestBid();
             // If still null (empty book), we can't calc max lots accurately.
        }
        
        // Split price for buy/sell? Request has single price.
        // Assuming user provides price they want to trade at.
        
        long maxBuy = accountManager.getMaxLots(true, null, price);
        long maxSell = accountManager.getMaxLots(false, null, price);
        
        GetMaxLotsResponse response = GetMaxLotsResponse.newBuilder()
                .setCurrency("RUB")
                .setBuyLimits(GetMaxLotsResponse.BuyLimitsView.newBuilder().setBuyMaxLots(maxBuy).build())
                .setBuyMarginLimits(GetMaxLotsResponse.BuyLimitsView.newBuilder().setBuyMaxLots(maxBuy).build()) // Same for now
                .setSellLimits(GetMaxLotsResponse.SellLimitsView.newBuilder().setSellMaxLots(maxSell).build())
                .setSellMarginLimits(GetMaxLotsResponse.SellLimitsView.newBuilder().setSellMaxLots(maxSell).build())
                .build();
                
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private BigDecimal calculateExecutedValue(List<Trade> trades) {
        return trades.stream()
                .map(t -> t.getPrice().multiply(BigDecimal.valueOf(t.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private OrderDirection mapDirection(ru.tinkoff.piapi.contract.v1.OrderDirection d) {
        return d == ru.tinkoff.piapi.contract.v1.OrderDirection.ORDER_DIRECTION_BUY 
                ? OrderDirection.BUY : OrderDirection.SELL;
    }
    
    private ru.tinkoff.piapi.contract.v1.OrderDirection mapDirectionProto(OrderDirection d) {
        return d == OrderDirection.BUY 
                ? ru.tinkoff.piapi.contract.v1.OrderDirection.ORDER_DIRECTION_BUY 
                : ru.tinkoff.piapi.contract.v1.OrderDirection.ORDER_DIRECTION_SELL;
    }

    private OrderType mapType(ru.tinkoff.piapi.contract.v1.OrderType t) {
        return t == ru.tinkoff.piapi.contract.v1.OrderType.ORDER_TYPE_MARKET 
                ? OrderType.MARKET : OrderType.LIMIT;
    }
    
    private ru.tinkoff.piapi.contract.v1.OrderType mapTypeProto(OrderType t) {
        return t == OrderType.MARKET 
                ? ru.tinkoff.piapi.contract.v1.OrderType.ORDER_TYPE_MARKET 
                : ru.tinkoff.piapi.contract.v1.OrderType.ORDER_TYPE_LIMIT;
    }

    private OrderExecutionReportStatus mapStatus(ru.tinkoff.invest.emulator.core.model.OrderStatus s) {
        return switch (s) {
            case NEW -> OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_NEW;
            case FILLED -> OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_FILL;
            case PARTIALLY_FILLED -> OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_PARTIALLYFILL;
            case CANCELLED -> OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_CANCELLED;
            case REJECTED -> OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_REJECTED;
        };
    }
}
