package ru.tinkoff.invest.emulator.core.orderbook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.tinkoff.invest.emulator.core.model.Order;
import ru.tinkoff.invest.emulator.core.model.OrderBook;
import ru.tinkoff.invest.emulator.core.model.OrderDirection;
import ru.tinkoff.invest.emulator.core.model.OrderType;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

import ru.tinkoff.invest.emulator.config.EmulatorProperties;

import org.springframework.context.ApplicationEventPublisher;
import static org.mockito.Mockito.mock;

class OrderBookManagerTest {

    private OrderBookManager manager;
    private final String INSTRUMENT = "TEST";
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    @BeforeEach
    void setUp() {
        EmulatorProperties props = new EmulatorProperties();
        EmulatorProperties.Instrument inst = new EmulatorProperties.Instrument();
        inst.setUid(INSTRUMENT);
        props.setInstrument(inst);
        manager = new OrderBookManager(props, eventPublisher);
    }

    private Order createOrder(OrderDirection dir, BigDecimal price) {
        return Order.builder()
                .id(UUID.randomUUID())
                .instrumentId(INSTRUMENT)
                .accountId("test-account")
                .source(ru.tinkoff.invest.emulator.core.model.OrderSource.API)
                .direction(dir)
                .type(OrderType.LIMIT)
                .price(price)
                .quantity(10)
                .build();
    }

    @Test
    void testAddAndRemoveOrder() {
        Order order = createOrder(OrderDirection.BUY, new BigDecimal("100"));
        manager.addOrder(order);
        
        assertEquals(order, manager.getOrder(order.getId()));
        assertEquals(new BigDecimal("100"), manager.getBestBid());
        
        boolean removed = manager.removeOrder(order.getId());
        assertTrue(removed);
        assertNull(manager.getOrder(order.getId()));
        assertNull(manager.getBestBid());
    }

    @Test
    void testBestBidAsk() {
        manager.addOrder(createOrder(OrderDirection.BUY, new BigDecimal("100")));
        manager.addOrder(createOrder(OrderDirection.BUY, new BigDecimal("101"))); // Best bid
        
        manager.addOrder(createOrder(OrderDirection.SELL, new BigDecimal("105"))); // Best ask
        manager.addOrder(createOrder(OrderDirection.SELL, new BigDecimal("106")));
        
        assertEquals(new BigDecimal("101"), manager.getBestBid());
        assertEquals(new BigDecimal("105"), manager.getBestAsk());
    }

    @Test
    void testSnapshot() {
        // Add 15 orders on different levels
        for (int i = 0; i < 15; i++) {
            manager.addOrder(createOrder(OrderDirection.BUY, new BigDecimal(100 - i)));
        }
        
        OrderBook snapshot = manager.getSnapshot(10);
        assertEquals(10, snapshot.getBids().size());
        assertEquals(new BigDecimal("100"), snapshot.getBids().firstKey());
        assertEquals(new BigDecimal("91"), snapshot.getBids().lastKey());
        
        OrderBook snapshotDeep = manager.getSnapshot(20);
        assertEquals(15, snapshotDeep.getBids().size());
    }
}
