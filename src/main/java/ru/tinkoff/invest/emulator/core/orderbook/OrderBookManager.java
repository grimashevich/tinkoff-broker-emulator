package ru.tinkoff.invest.emulator.core.orderbook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import ru.tinkoff.invest.emulator.config.EmulatorProperties;
import ru.tinkoff.invest.emulator.core.event.OrderBookChangedEvent;
import ru.tinkoff.invest.emulator.core.model.Order;
import ru.tinkoff.invest.emulator.core.model.OrderBook;
import ru.tinkoff.invest.emulator.core.model.OrderDirection;
import ru.tinkoff.invest.emulator.core.model.OrderSource;
import ru.tinkoff.invest.emulator.core.model.OrderType;
import ru.tinkoff.invest.emulator.core.model.PriceLevel;
import jakarta.annotation.PostConstruct;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OrderBookManager {
    private final OrderBook orderBook;
    private final Map<UUID, Order> orderIndex = new HashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ApplicationEventPublisher eventPublisher;
    private final EmulatorProperties properties;

    public OrderBookManager(EmulatorProperties properties, ApplicationEventPublisher eventPublisher) {
        this.orderBook = new OrderBook(properties.getInstrument().getUid());
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        var orderbookConfig = properties.getOrderbook();
        BigDecimal initialBid = orderbookConfig.getInitialBid();
        BigDecimal initialAsk = orderbookConfig.getInitialAsk();
        BigDecimal priceIncrement = properties.getInstrument().getMinPriceIncrement();
        String instrumentId = properties.getInstrument().getUid();

        if (initialBid == null || initialAsk == null || priceIncrement == null) {
            log.warn("OrderBook initialization skipped: missing bid/ask/increment");
            return;
        }

        int levelsCount = orderbookConfig.getLevelsCount();
        var random = new java.util.Random();

        // Создаём уровни BID (от лучшего вниз)
        for (int i = 0; i < levelsCount; i++) {
            BigDecimal price = initialBid.subtract(priceIncrement.multiply(BigDecimal.valueOf(i)));
            long volume = (i == 0)
                    ? randomInRange(random, orderbookConfig.getBestPriceVolumeMin(), orderbookConfig.getBestPriceVolumeMax())
                    : randomInRange(random, orderbookConfig.getOtherVolumeMin(), orderbookConfig.getOtherVolumeMax());

            Order bidOrder = Order.builder()
                    .id(UUID.randomUUID())
                    .instrumentId(instrumentId)
                    .accountId("market-maker-init")
                    .direction(OrderDirection.BUY)
                    .type(OrderType.LIMIT)
                    .price(price)
                    .quantity(volume)
                    .source(OrderSource.ADMIN_PANEL)
                    .build();
            addOrder(bidOrder);
        }

        // Создаём уровни ASK (от лучшего вверх)
        for (int i = 0; i < levelsCount; i++) {
            BigDecimal price = initialAsk.add(priceIncrement.multiply(BigDecimal.valueOf(i)));
            long volume = (i == 0)
                    ? randomInRange(random, orderbookConfig.getBestPriceVolumeMin(), orderbookConfig.getBestPriceVolumeMax())
                    : randomInRange(random, orderbookConfig.getOtherVolumeMin(), orderbookConfig.getOtherVolumeMax());

            Order askOrder = Order.builder()
                    .id(UUID.randomUUID())
                    .instrumentId(instrumentId)
                    .accountId("market-maker-init")
                    .direction(OrderDirection.SELL)
                    .type(OrderType.LIMIT)
                    .price(price)
                    .quantity(volume)
                    .source(OrderSource.ADMIN_PANEL)
                    .build();
            addOrder(askOrder);
        }

        // Заявки маркетмейкера на большие объёмы
        var mmConfig = orderbookConfig.getMarketMaker();

        // MM Bid - на N шагов ниже лучшего bid
        BigDecimal mmBidPrice = initialBid.subtract(priceIncrement.multiply(BigDecimal.valueOf(mmConfig.getBidOffset())));
        Order mmBidOrder = Order.builder()
                .id(UUID.randomUUID())
                .instrumentId(instrumentId)
                .accountId("market-maker-wall")
                .direction(OrderDirection.BUY)
                .type(OrderType.LIMIT)
                .price(mmBidPrice)
                .quantity(mmConfig.getVolume())
                .source(OrderSource.ADMIN_PANEL)
                .build();
        addOrder(mmBidOrder);

        // MM Ask - на N шагов выше лучшего ask
        BigDecimal mmAskPrice = initialAsk.add(priceIncrement.multiply(BigDecimal.valueOf(mmConfig.getAskOffset())));
        Order mmAskOrder = Order.builder()
                .id(UUID.randomUUID())
                .instrumentId(instrumentId)
                .accountId("market-maker-wall")
                .direction(OrderDirection.SELL)
                .type(OrderType.LIMIT)
                .price(mmAskPrice)
                .quantity(mmConfig.getVolume())
                .source(OrderSource.ADMIN_PANEL)
                .build();
        addOrder(mmAskOrder);

        log.info("OrderBook initialized: bid={}, ask={}, levels={}, MM bid wall={} @{}, MM ask wall={} @{}",
                initialBid, initialAsk, levelsCount,
                mmConfig.getVolume(), mmBidPrice,
                mmConfig.getVolume(), mmAskPrice);
    }

    private long randomInRange(java.util.Random random, long min, long max) {
        return min + (long) (random.nextDouble() * (max - min));
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            orderBook.getBids().clear();
            orderBook.getAsks().clear();
            orderIndex.clear();
            log.info("OrderBook cleared for test");
            publishEvent();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Сброс стакана к начальному состоянию: очистка + реинициализация.
     */
    public void reset() {
        lock.writeLock().lock();
        try {
            orderBook.getBids().clear();
            orderBook.getAsks().clear();
            orderIndex.clear();
            log.info("OrderBook reset: cleared all orders");
        } finally {
            lock.writeLock().unlock();
        }
        // init() вызываем вне блокировки, т.к. он сам берёт write lock в addOrder()
        init();
        log.info("OrderBook reset: reinitialized with market-maker orders");
    }

    public void addOrder(Order order) {
        lock.writeLock().lock();
        try {
            if (orderIndex.containsKey(order.getId())) {
                log.warn("Order {} already exists in order book", order.getId());
                return;
            }

            NavigableMap<BigDecimal, PriceLevel> side = getSide(order);
            PriceLevel level = side.computeIfAbsent(order.getPrice(), PriceLevel::new);
            level.addOrder(order);
            orderIndex.put(order.getId(), order);
            log.debug("Added order {} to level {}", order.getId(), order.getPrice());
            
            publishEvent();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean removeOrder(UUID orderId) {
        lock.writeLock().lock();
        try {
            Order order = orderIndex.remove(orderId);
            if (order == null) {
                return false;
            }

            NavigableMap<BigDecimal, PriceLevel> side = getSide(order);
            PriceLevel level = side.get(order.getPrice());
            if (level != null) {
                boolean removed = level.removeOrder(order);
                if (level.isEmpty()) {
                    side.remove(order.getPrice());
                }
                if (removed) {
                    publishEvent();
                }
                return removed;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void removeOrderIndex(UUID orderId) {
        lock.writeLock().lock();
        try {
            orderIndex.remove(orderId);
            // This is called by MatchingEngine usually when order is filled and removed from Level.
            // MatchingEngine should trigger event or we should?
            // If MatchingEngine modifies Level directly, OrderBookManager might not know the extent of change.
            // But usually MatchingEngine calls this to clean up index.
            // MatchingEngine modifies quantities of orders. That changes the book state (volumes).
            // OrderBookManager snapshot reads current quantities.
            // So we should publish event when volumes change too.
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    public void notifyUpdate() {
        // Allow external components (MatchingEngine) to trigger update event after batch changes
        publishEvent();
    }
    
    private void publishEvent() {
        // Send a snapshot or just notification?
        // Event contains the OrderBook object (which is mutable!)
        // Subscribers should probably take a snapshot or we send a snapshot.
        // Sending the mutable object is dangerous if subscribers read it without lock.
        // Let's send a shallow snapshot or just the ID.
        // Or better: Subscribers call getSnapshot inside the lock?
        // Let's make the event carry the ID, and subscribers fetch?
        // Or we send a snapshot in the event.
        // `getSnapshot(50)` covers most needs.
        
        // However, making snapshot under lock here is safe.
        // Let's assume depth 50 is enough for stream.
        eventPublisher.publishEvent(new OrderBookChangedEvent(this, getSnapshot(50)));
    }
    
    public Order getOrder(UUID orderId) {
        lock.readLock().lock();
        try {
            return orderIndex.get(orderId);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<Order> getAllOrders() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(orderIndex.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<Order> getOrders(String accountId) {
        lock.readLock().lock();
        try {
            return orderIndex.values().stream()
                    .filter(o -> Objects.equals(o.getAccountId(), accountId))
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public OrderBook getSnapshot(int depth) {
        lock.readLock().lock();
        try {
            OrderBook snapshot = new OrderBook(orderBook.getInstrumentId());
            
            fillSnapshotSide(orderBook.getBids(), snapshot.getBids(), depth);
            fillSnapshotSide(orderBook.getAsks(), snapshot.getAsks(), depth);
            
            return snapshot;
        } finally {
            lock.readLock().unlock();
        }
    }

    private void fillSnapshotSide(NavigableMap<BigDecimal, PriceLevel> source, 
                                  NavigableMap<BigDecimal, PriceLevel> target, 
                                  int depth) {
        source.entrySet().stream()
                .limit(depth)
                .forEach(entry -> {
                    PriceLevel originalLevel = entry.getValue();
                    PriceLevel snapshotLevel = new PriceLevel(originalLevel.getPrice());
                    originalLevel.getOrders().forEach(snapshotLevel::addOrder);
                    target.put(entry.getKey(), snapshotLevel);
                });
    }

    public BigDecimal getBestBid() {
        lock.readLock().lock();
        try {
            return orderBook.getBids().isEmpty() ? null : orderBook.getBids().firstKey();
        } finally {
            lock.readLock().unlock();
        }
    }

    public BigDecimal getBestAsk() {
        lock.readLock().lock();
        try {
            return orderBook.getAsks().isEmpty() ? null : orderBook.getAsks().firstKey();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    public NavigableMap<BigDecimal, PriceLevel> getBids() {
        return orderBook.getBids();
    }
    
    public NavigableMap<BigDecimal, PriceLevel> getAsks() {
        return orderBook.getAsks();
    }
    
    public ReentrantReadWriteLock getLock() {
        return lock;
    }

    private NavigableMap<BigDecimal, PriceLevel> getSide(Order order) {
        return switch (order.getDirection()) {
            case BUY -> orderBook.getBids();
            case SELL -> orderBook.getAsks();
        };
    }
}