package ru.tinkoff.invest.emulator;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import ru.tinkoff.piapi.contract.v1.*;

import java.io.File;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

public class E2eContainerTest {

    private static final DockerComposeContainer<?> compose;
    private static ManagedChannel channel;

    static {
        compose = new DockerComposeContainer<>(new File("docker-compose.yml"))
                .withExposedService("emulator", 8080, Wait.forHealthcheck().withStartupTimeout(Duration.ofSeconds(120)))
                .withExposedService("emulator", 50051);
    }

    @BeforeAll
    static void start() {
        compose.start();
        channel = ManagedChannelBuilder
                .forAddress(compose.getServiceHost("emulator", 50051), compose.getServicePort("emulator", 50051))
                .usePlaintext()
                .build();
    }

    @AfterAll
    static void stop() {
        if (channel != null) {
            channel.shutdown();
        }
        compose.stop();
    }

    @Test
    void testGrpcConnectionAndApi() {
        InstrumentsServiceGrpc.InstrumentsServiceBlockingStub instrumentsStub = InstrumentsServiceGrpc.newBlockingStub(channel);

        FindInstrumentResponse response = instrumentsStub.findInstrument(FindInstrumentRequest.newBuilder()
                .setQuery("TBRU")
                .build());

        assertNotNull(response);
        assertFalse(response.getInstrumentsList().isEmpty());
        assertEquals("TBRU", response.getInstruments(0).getTicker());
    }

    @Test
    void testFullOrderCycle() {
        OrdersServiceGrpc.OrdersServiceBlockingStub ordersStub = OrdersServiceGrpc.newBlockingStub(channel);
        MarketDataServiceGrpc.MarketDataServiceBlockingStub marketDataStub = MarketDataServiceGrpc.newBlockingStub(channel);

        // Assuming instrument UID is known from config
        String instrumentId = "e8acd2fb-6de6-4ea4-9bfb-0daad9b2ed7b";
        String accountId = "mock-account-001";
        
        // 1. Post a limit BUY order. It should go to the book.
        PostOrderResponse limitBuy = ordersStub.postOrder(PostOrderRequest.newBuilder()
                .setAccountId(accountId)
                .setInstrumentId(instrumentId)
                .setDirection(OrderDirection.ORDER_DIRECTION_BUY)
                .setOrderType(OrderType.ORDER_TYPE_LIMIT)
                .setQuantity(5)
                .setPrice(Quotation.newBuilder().setUnits(100).build())
                .setOrderId("e2e-test-order-1")
                .build());

        assertEquals(OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_NEW, limitBuy.getExecutionReportStatus());

        // 2. Check the order book
        GetOrderBookResponse orderBook = marketDataStub.getOrderBook(GetOrderBookRequest.newBuilder()
                .setInstrumentId(instrumentId)
                .setDepth(10)
                .build());
        
        assertEquals(1, orderBook.getBidsCount());
        assertEquals(100, orderBook.getBids(0).getPrice().getUnits());
        assertEquals(5, orderBook.getBids(0).getQuantity());

        // 3. Post a market SELL order to match the buy order
        PostOrderResponse marketSell = ordersStub.postOrder(PostOrderRequest.newBuilder()
                .setAccountId(accountId)
                .setInstrumentId(instrumentId)
                .setDirection(OrderDirection.ORDER_DIRECTION_SELL)
                .setOrderType(OrderType.ORDER_TYPE_MARKET)
                .setQuantity(2)
                .setOrderId("e2e-test-order-2")
                .build());

        assertEquals(OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_FILL, marketSell.getExecutionReportStatus());
        assertEquals(2, marketSell.getLotsExecuted());
        
        // 4. Check the order book again, bid should be partially filled
        GetOrderBookResponse orderBookAfter = marketDataStub.getOrderBook(GetOrderBookRequest.newBuilder()
                .setInstrumentId(instrumentId)
                .setDepth(10)
                .build());
        
        assertEquals(1, orderBookAfter.getBidsCount());
        assertEquals(3, orderBookAfter.getBids(0).getQuantity());
    }
}
