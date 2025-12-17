package ru.tinkoff.invest.emulator;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.tinkoff.invest.emulator.config.EmulatorProperties;
import ru.tinkoff.invest.emulator.core.orderbook.OrderBookManager;
import ru.tinkoff.invest.emulator.core.model.OrderDirection;
import ru.tinkoff.invest.emulator.core.model.OrderType;
import ru.tinkoff.piapi.contract.v1.*;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "grpc.server.port=9095", // Different port for streams test to avoid conflict if run parallel? No, random port or same is fine.
    "grpc.server.inProcessName=test-streams",
    "server.port=8085"
})
@ActiveProfiles("test")
public class StreamsIntegrationTest {

    @Autowired
    private EmulatorProperties properties;
    
    @Autowired
    private OrderBookManager orderBookManager;
    
    private ManagedChannel channel;
    private MarketDataStreamServiceGrpc.MarketDataStreamServiceStub marketDataStreamStub;
    private OrdersServiceGrpc.OrdersServiceBlockingStub ordersStub;
    private OrdersStreamServiceGrpc.OrdersStreamServiceStub ordersStreamStub;

    @BeforeEach
    void setUp() {
        orderBookManager.clear();
        channel = ManagedChannelBuilder.forAddress("localhost", 9095)
                .usePlaintext()
                .build();
        
        marketDataStreamStub = MarketDataStreamServiceGrpc.newStub(channel);
        ordersStub = OrdersServiceGrpc.newBlockingStub(channel);
        ordersStreamStub = OrdersStreamServiceGrpc.newStub(channel);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void testMarketDataStream() throws InterruptedException {
        String instrumentId = properties.getInstrument().getUid();
        
        BlockingQueue<MarketDataResponse> responses = new LinkedBlockingQueue<>();
        
        StreamObserver<MarketDataRequest> requestObserver = marketDataStreamStub.marketDataStream(new StreamObserver<>() {
            @Override
            public void onNext(MarketDataResponse value) {
                responses.add(value);
            }

            @Override
            public void onError(Throwable t) {
                t.printStackTrace();
            }

            @Override
            public void onCompleted() {
            }
        });
        
        // 1. Subscribe
        requestObserver.onNext(MarketDataRequest.newBuilder()
                .setSubscribeOrderBookRequest(SubscribeOrderBookRequest.newBuilder()
                        .setSubscriptionAction(SubscriptionAction.SUBSCRIPTION_ACTION_SUBSCRIBE)
                        .addInstruments(OrderBookInstrument.newBuilder()
                                .setInstrumentId(instrumentId)
                                .setDepth(10)
                                .build())
                        .build())
                .build());
        
        // Check confirmation
        MarketDataResponse resp = responses.poll(5, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertTrue(resp.hasSubscribeOrderBookResponse());
        assertEquals(SubscriptionStatus.SUBSCRIPTION_STATUS_SUCCESS, resp.getSubscribeOrderBookResponse().getOrderBookSubscriptions(0).getSubscriptionStatus());
        
        // 2. Trigger change (Post Order)
        ordersStub.postOrder(PostOrderRequest.newBuilder()
                .setInstrumentId(instrumentId)
                .setAccountId("test-acc")
                .setDirection(ru.tinkoff.piapi.contract.v1.OrderDirection.ORDER_DIRECTION_BUY)
                .setOrderType(ru.tinkoff.piapi.contract.v1.OrderType.ORDER_TYPE_LIMIT)
                .setQuantity(10)
                .setPrice(Quotation.newBuilder().setUnits(123).build())
                .setOrderId(UUID.randomUUID().toString())
                .build());
        
        // Check update
        resp = responses.poll(5, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertTrue(resp.hasOrderbook());
        assertEquals(123, resp.getOrderbook().getBids(0).getPrice().getUnits());
    }
    
    @Test
    void testOrderStateStream() throws InterruptedException {
        String instrumentId = properties.getInstrument().getUid();
        String accountId = "test-stream-acc";
        
        BlockingQueue<OrderStateStreamResponse> responses = new LinkedBlockingQueue<>();
        
        ordersStreamStub.orderStateStream(OrderStateStreamRequest.newBuilder()
                .addAccounts(accountId)
                .build(), new StreamObserver<>() {
            @Override
            public void onNext(OrderStateStreamResponse value) {
                responses.add(value);
            }

            @Override
            public void onError(Throwable t) {
                t.printStackTrace();
            }

            @Override
            public void onCompleted() {
            }
        });
        
        // Wait a bit for subscription
        Thread.sleep(500);
        
        // Post Order
        ordersStub.postOrder(PostOrderRequest.newBuilder()
                .setInstrumentId(instrumentId)
                .setAccountId(accountId)
                .setDirection(ru.tinkoff.piapi.contract.v1.OrderDirection.ORDER_DIRECTION_SELL)
                .setOrderType(ru.tinkoff.piapi.contract.v1.OrderType.ORDER_TYPE_LIMIT)
                .setQuantity(5)
                .setPrice(Quotation.newBuilder().setUnits(200).build())
                .setOrderId(UUID.randomUUID().toString())
                .build());
        
        // Should receive event
        OrderStateStreamResponse resp = responses.poll(5, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertTrue(resp.hasOrderState());
        assertEquals(OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_NEW, resp.getOrderState().getExecutionReportStatus());
        assertEquals(5, resp.getOrderState().getLotsRequested());
    }
}
