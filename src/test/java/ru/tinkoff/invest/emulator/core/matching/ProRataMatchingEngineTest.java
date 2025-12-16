package ru.tinkoff.invest.emulator.core.matching;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.tinkoff.invest.emulator.core.model.*;
import ru.tinkoff.invest.emulator.core.orderbook.OrderBookManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

import ru.tinkoff.invest.emulator.config.EmulatorProperties;

import org.springframework.context.ApplicationEventPublisher;
import static org.mockito.Mockito.mock;

class ProRataMatchingEngineTest {

    private OrderBookManager orderBookManager;
    private ProRataMatchingEngine matchingEngine;
    private final String INSTRUMENT_ID = "TBRU";
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    @BeforeEach
    void setUp() {
        EmulatorProperties props = new EmulatorProperties();
        EmulatorProperties.Instrument inst = new EmulatorProperties.Instrument();
        inst.setUid(INSTRUMENT_ID);
        props.setInstrument(inst);
        
        orderBookManager = new OrderBookManager(props, eventPublisher);
        matchingEngine = new ProRataMatchingEngine(orderBookManager, eventPublisher);
    }

    private Order createOrder(OrderDirection direction, BigDecimal price, long quantity) {
        return Order.builder()
                .id(UUID.randomUUID())
                .instrumentId(INSTRUMENT_ID)
                .accountId("test-account")
                .source(OrderSource.API)
                .direction(direction)
                .type(OrderType.LIMIT)
                .price(price)
                .quantity(quantity)
                .build();
    }
    
    private Order createMarketOrder(OrderDirection direction, long quantity) {
         return Order.builder()
                .id(UUID.randomUUID())
                .instrumentId(INSTRUMENT_ID)
                .accountId("test-account")
                .source(OrderSource.API)
                .direction(direction)
                .type(OrderType.MARKET)
                .price(BigDecimal.ZERO) // Market order price ignored usually, but good to be explicit
                .quantity(quantity)
                .build();
    }

    @Test
    void testBasicProRataDistribution() {
        // A: 100, B: 50. Ask 7.70.
        BigDecimal price = new BigDecimal("7.70");
        Order orderA = createOrder(OrderDirection.SELL, price, 100);
        Order orderB = createOrder(OrderDirection.SELL, price, 50);
        
        orderBookManager.addOrder(orderA);
        orderBookManager.addOrder(orderB);
        
        // Buy 30
        Order aggressor = createMarketOrder(OrderDirection.BUY, 30);
        List<Trade> trades = matchingEngine.executeOrder(aggressor);
        
        // Check trades
        assertEquals(2, trades.size());
        
        long filledA = orderA.getFilledQuantity();
        long filledB = orderB.getFilledQuantity();
        
        // A: floor(30 * 100/150) = 20
        // B: floor(30 * 50/150) = 10
        assertEquals(20, filledA);
        assertEquals(10, filledB);
        assertEquals(30, aggressor.getFilledQuantity());
    }

    @Test
    void testProRataWithTailFIFO() {
        // A: 100, B: 50, C: 17. Ask 7.70. Total 167.
        BigDecimal price = new BigDecimal("7.70");
        Order orderA = createOrder(OrderDirection.SELL, price, 100);
        Order orderB = createOrder(OrderDirection.SELL, price, 50);
        Order orderC = createOrder(OrderDirection.SELL, price, 17);
        
        orderBookManager.addOrder(orderA);
        orderBookManager.addOrder(orderB);
        orderBookManager.addOrder(orderC);
        
        // Buy 50
        Order aggressor = createMarketOrder(OrderDirection.BUY, 50);
        List<Trade> trades = matchingEngine.executeOrder(aggressor);
        
        // Pro-Rata:
        // A: 29.94 -> 29
        // B: 14.97 -> 14
        // C: 5.09 -> 5
        // Sum = 48. Tail = 2.
        // FIFO: A+1 -> 30, B+1 -> 15.
        
        assertEquals(30, orderA.getFilledQuantity());
        assertEquals(15, orderB.getFilledQuantity());
        assertEquals(5, orderC.getFilledQuantity());
        assertEquals(50, aggressor.getFilledQuantity());
    }

    @Test
    void testSmallOrderGetsNothing() {
        // A: 100, B: 50, C: 1. Total 151.
        BigDecimal price = new BigDecimal("7.70");
        Order orderA = createOrder(OrderDirection.SELL, price, 100);
        Order orderB = createOrder(OrderDirection.SELL, price, 50);
        Order orderC = createOrder(OrderDirection.SELL, price, 1);
        
        orderBookManager.addOrder(orderA);
        orderBookManager.addOrder(orderB);
        orderBookManager.addOrder(orderC);
        
        // Buy 10
        Order aggressor = createMarketOrder(OrderDirection.BUY, 10);
        matchingEngine.executeOrder(aggressor);
        
        // A: 6.62 -> 6
        // B: 3.31 -> 3
        // C: 0.06 -> 0
        // Tail 1 -> A (FIFO) -> A=7.
        
        assertEquals(7, orderA.getFilledQuantity());
        assertEquals(3, orderB.getFilledQuantity());
        assertEquals(0, orderC.getFilledQuantity());
    }

    @Test
    void testMultipleLevelExecution() {
        // L1 7.70: A=50, B=30 (Total 80)
        // L2 7.71: C=100
        BigDecimal p1 = new BigDecimal("7.70");
        BigDecimal p2 = new BigDecimal("7.71");
        
        Order orderA = createOrder(OrderDirection.SELL, p1, 50);
        Order orderB = createOrder(OrderDirection.SELL, p1, 30);
        Order orderC = createOrder(OrderDirection.SELL, p2, 100);
        
        orderBookManager.addOrder(orderA);
        orderBookManager.addOrder(orderB);
        orderBookManager.addOrder(orderC);
        
        // Buy 150
        Order aggressor = createMarketOrder(OrderDirection.BUY, 150);
        List<Trade> trades = matchingEngine.executeOrder(aggressor);
        
        // L1 fully filled (80)
        assertEquals(50, orderA.getFilledQuantity());
        assertEquals(30, orderB.getFilledQuantity());
        assertTrue(orderA.isFullyFilled());
        assertTrue(orderB.isFullyFilled());
        
        // L2 filled 70 (150 - 80)
        assertEquals(70, orderC.getFilledQuantity());
        
        assertEquals(150, aggressor.getFilledQuantity());
        
        // Verify OrderBook state
        // L1 should be gone
        assertNull(orderBookManager.getAsks().get(p1));
        // L2 should remain with C
        assertNotNull(orderBookManager.getAsks().get(p2));
        assertFalse(orderBookManager.getAsks().get(p2).isEmpty());
    }

    @Test
    void testSingleOrderOnLevel() {
        Order orderA = createOrder(OrderDirection.SELL, new BigDecimal("100"), 100);
        orderBookManager.addOrder(orderA);
        
        Order aggressor = createMarketOrder(OrderDirection.BUY, 50);
        matchingEngine.executeOrder(aggressor);
        
        assertEquals(50, orderA.getFilledQuantity());
    }
    
    @Test
    void testEqualOrdersSameDistribution() {
        BigDecimal price = new BigDecimal("100");
        Order orderA = createOrder(OrderDirection.SELL, price, 100);
        Order orderB = createOrder(OrderDirection.SELL, price, 100);
        
        orderBookManager.addOrder(orderA);
        orderBookManager.addOrder(orderB);
        
        Order aggressor = createMarketOrder(OrderDirection.BUY, 50);
        matchingEngine.executeOrder(aggressor);
        
        assertEquals(25, orderA.getFilledQuantity());
        assertEquals(25, orderB.getFilledQuantity());
    }
    
    @Test
    void testLimitOrderDoesNotExecuteWorsePrice() {
        // Sell at 100.
        // Buy Limit at 99. Should not match.
        Order sell = createOrder(OrderDirection.SELL, new BigDecimal("100"), 10);
        orderBookManager.addOrder(sell);
        
        Order buyLimit = createOrder(OrderDirection.BUY, new BigDecimal("99"), 10);
        List<Trade> trades = matchingEngine.executeOrder(buyLimit);
        
        assertTrue(trades.isEmpty());
        assertEquals(0, sell.getFilledQuantity());
        assertEquals(0, buyLimit.getFilledQuantity());
    }
    
    @Test
    void testLimitOrderExecutesBetterPrice() {
        // Sell at 100.
        // Buy Limit at 101. Should match at 100.
        Order sell = createOrder(OrderDirection.SELL, new BigDecimal("100"), 10);
        orderBookManager.addOrder(sell);
        
        Order buyLimit = createOrder(OrderDirection.BUY, new BigDecimal("101"), 10);
        List<Trade> trades = matchingEngine.executeOrder(buyLimit);
        
        assertEquals(1, trades.size());
        assertEquals(new BigDecimal("100"), trades.get(0).getPrice());
        assertEquals(10, sell.getFilledQuantity());
    }
}
