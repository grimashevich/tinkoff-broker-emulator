package ru.tinkoff.invest.emulator.grpc.service;

import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.context.event.EventListener;
import ru.tinkoff.invest.emulator.core.event.OrderStateChangedEvent;
import ru.tinkoff.invest.emulator.core.model.Order;
import ru.tinkoff.invest.emulator.core.model.OrderDirection;
import ru.tinkoff.invest.emulator.core.model.OrderStatus;
import ru.tinkoff.invest.emulator.core.model.OrderType;
import ru.tinkoff.invest.emulator.core.stream.StreamManager;
import ru.tinkoff.invest.emulator.grpc.mapper.GrpcMapper;
import ru.tinkoff.piapi.contract.v1.*;
import ru.tinkoff.piapi.contract.v1.OrdersStreamServiceGrpc.OrdersStreamServiceImplBase;

import java.math.BigDecimal;

@Slf4j
@GrpcService
@RequiredArgsConstructor
public class OrdersStreamServiceImpl extends OrdersStreamServiceImplBase {

    private final StreamManager streamManager;

    @Override
    public void orderStateStream(OrderStateStreamRequest request, StreamObserver<OrderStateStreamResponse> responseObserver) {
        // In request.getAccountsList() we have accounts to subscribe to.
        for (String accountId : request.getAccountsList()) {
            streamManager.addOrderStateSubscription(responseObserver, accountId);
        }
        
        // This is Server Stream, so we don't return immediately, we keep it open.
        // But we don't have a way to detect client disconnect easily unless we wrap or ping.
        // For now, simple registration.
    }

    @EventListener
    public void onOrderStateChanged(OrderStateChangedEvent event) {
        Order order = event.getOrder();
        if (order.getAccountId() == null) return;

        OrderStateStreamResponse response = OrderStateStreamResponse.newBuilder()
                .setOrderState(OrderStateStreamResponse.OrderState.newBuilder()
                        .setOrderId(order.getId().toString())
                        .setAccountId(order.getAccountId())
                        .setExecutionReportStatus(mapStatus(order.getStatus()))
                        .setLotsRequested(order.getQuantity())
                        .setLotsExecuted(order.getFilledQuantity())
                        .setInitialOrderPrice(GrpcMapper.toMoneyValue(order.getPrice().multiply(BigDecimal.valueOf(order.getQuantity())), "RUB"))
                        .setDirection(mapDirection(order.getDirection()))
                        .setOrderType(mapType(order.getType()))
                        .setInstrumentUid(order.getInstrumentId())
                        .setTicker(order.getInstrumentId())
                        .build())
                .build();
        
        streamManager.broadcastOrderState(order.getAccountId(), response);
    }

    private OrderExecutionReportStatus mapStatus(OrderStatus s) {
        return switch (s) {
            case NEW -> OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_NEW;
            case FILLED -> OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_FILL;
            case PARTIALLY_FILLED -> OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_PARTIALLYFILL;
            case CANCELLED -> OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_CANCELLED;
            case REJECTED -> OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_REJECTED;
        };
    }
    
    private ru.tinkoff.piapi.contract.v1.OrderDirection mapDirection(OrderDirection d) {
        return d == OrderDirection.BUY 
                ? ru.tinkoff.piapi.contract.v1.OrderDirection.ORDER_DIRECTION_BUY 
                : ru.tinkoff.piapi.contract.v1.OrderDirection.ORDER_DIRECTION_SELL;
    }
    
    private ru.tinkoff.piapi.contract.v1.OrderType mapType(OrderType t) {
        return t == OrderType.MARKET 
                ? ru.tinkoff.piapi.contract.v1.OrderType.ORDER_TYPE_MARKET 
                : ru.tinkoff.piapi.contract.v1.OrderType.ORDER_TYPE_LIMIT;
    }
}
