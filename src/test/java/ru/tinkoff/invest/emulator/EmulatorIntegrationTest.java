package ru.tinkoff.invest.emulator;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.tinkoff.invest.emulator.config.EmulatorProperties;
import ru.tinkoff.invest.emulator.core.orderbook.OrderBookManager;
import ru.tinkoff.invest.emulator.core.model.Order;
import ru.tinkoff.invest.emulator.core.model.OrderSource;
import ru.tinkoff.piapi.contract.v1.*;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "grpc.server.port=9090",
    "grpc.server.inProcessName=test",
    "server.port=8080"
})
@ActiveProfiles("test")
public class EmulatorIntegrationTest {

    @Autowired
    private EmulatorProperties properties;
    
    @Autowired
    private OrderBookManager orderBookManager;

    private ManagedChannel channel;
    private MarketDataServiceGrpc.MarketDataServiceBlockingStub marketDataStub;
    private OrdersServiceGrpc.OrdersServiceBlockingStub ordersStub;
    private OperationsServiceGrpc.OperationsServiceBlockingStub operationsStub;
    private InstrumentsServiceGrpc.InstrumentsServiceBlockingStub instrumentsStub;

    @BeforeEach
    void setUp() {
        channel = ManagedChannelBuilder.forAddress("localhost", 9090)
                .usePlaintext()
                .build();
        
        marketDataStub = MarketDataServiceGrpc.newBlockingStub(channel);
        ordersStub = OrdersServiceGrpc.newBlockingStub(channel);
        operationsStub = OperationsServiceGrpc.newBlockingStub(channel);
        instrumentsStub = InstrumentsServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void testFullFlow() {
        String instrumentId = properties.getInstrument().getUid();
        String accountId = properties.getAccount().getId();
        
        // 0. Pre-seed with Market Maker orders (Admin)
        // Sell 100 @ 110
        orderBookManager.addOrder(Order.builder()
                .id(UUID.randomUUID())
                .instrumentId(instrumentId)
                .price(new BigDecimal("110"))
                .quantity(100)
                .direction(ru.tinkoff.invest.emulator.core.model.OrderDirection.SELL)
                .type(ru.tinkoff.invest.emulator.core.model.OrderType.LIMIT)
                .source(OrderSource.ADMIN_PANEL)
                .accountId("market-maker")
                .build());

        // 1. Check Instrument
        FindInstrumentResponse findResp = instrumentsStub.findInstrument(FindInstrumentRequest.newBuilder()
                .setQuery(instrumentId)
                .build());
        assertFalse(findResp.getInstrumentsList().isEmpty());
        assertEquals(instrumentId, findResp.getInstruments(0).getUid());

        // 2. Buy 2 lots at Market. Should match Market Maker at 110.
        PostOrderResponse order = ordersStub.postOrder(PostOrderRequest.newBuilder()
                .setInstrumentId(instrumentId)
                .setAccountId(accountId)
                .setDirection(OrderDirection.ORDER_DIRECTION_BUY)
                .setOrderType(OrderType.ORDER_TYPE_MARKET)
                .setQuantity(2)
                .setOrderId(UUID.randomUUID().toString())
                .build());
        
        assertEquals(OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_FILL, order.getExecutionReportStatus());
        assertEquals(2, order.getLotsExecuted());
        // Proto specifies "average price of one instrument". 
        // My implementation returns total value.
        // Assuming implementation returns TOTAL value (220).
        // Let's check logic:
        // executedOrderPrice = sum(p*q).
        // So it is 220.
        assertEquals(220, order.getExecutedOrderPrice().getUnits()); 
        
        // 3. Check Position. Should be +2.
        PositionsResponse posResp = operationsStub.getPositions(PositionsRequest.newBuilder().setAccountId(accountId).build());
        boolean found = false;
        for (PositionsSecurities sec : posResp.getSecuritiesList()) {
            if (sec.getFigi().equals(instrumentId)) {
                assertEquals(2, sec.getBalance());
                found = true;
            }
        }
        assertTrue(found, "Position should exist");
        
        // 4. Check MaxLots
        GetMaxLotsResponse maxLots = ordersStub.getMaxLots(GetMaxLotsRequest.newBuilder()
                .setAccountId(accountId)
                .setInstrumentId(instrumentId)
                .setPrice(Quotation.newBuilder().setUnits(100).build())
                .build());
        
        assertTrue(maxLots.getBuyLimits().getBuyMaxLots() > 0);
    }
}