package ru.tinkoff.invest.emulator.core.matching;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import ru.tinkoff.invest.emulator.core.event.OrderStateChangedEvent;
import ru.tinkoff.invest.emulator.core.event.TradeExecutedEvent;
import ru.tinkoff.invest.emulator.core.model.*;
import ru.tinkoff.invest.emulator.core.orderbook.OrderBookManager;

import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProRataMatchingEngine {
    private final OrderBookManager orderBookManager;
    private final ApplicationEventPublisher eventPublisher;

    public List<Trade> executeOrder(Order aggressorOrder) {
        log.info("MATCHING: Starting execution of order {} [{}] {} {} @ {} qty={}",
                aggressorOrder.getId(),
                aggressorOrder.getSource(),
                aggressorOrder.getDirection(),
                aggressorOrder.getType(),
                aggressorOrder.getPrice(),
                aggressorOrder.getQuantity());

        orderBookManager.getLock().writeLock().lock();
        boolean bookChanged = false;
        try {
            if (aggressorOrder.getQuantity() <= 0) {
                log.warn("MATCHING: Order {} has invalid quantity <= 0, skipping", aggressorOrder.getId());
                return Collections.emptyList();
            }

            List<Trade> trades = new ArrayList<>();
            NavigableMap<BigDecimal, PriceLevel> oppositeSide = getOppositeSide(aggressorOrder.getDirection());

            log.debug("MATCHING: Opposite side has {} price levels", oppositeSide.size());

            Iterator<Map.Entry<BigDecimal, PriceLevel>> levelIterator = oppositeSide.entrySet().iterator();

            while (aggressorOrder.getRemainingQuantity() > 0 && levelIterator.hasNext()) {
                Map.Entry<BigDecimal, PriceLevel> entry = levelIterator.next();
                BigDecimal levelPrice = entry.getKey();
                PriceLevel level = entry.getValue();

                log.debug("MATCHING: Checking price level {} with {} orders, total qty={}",
                        levelPrice, level.getOrdersSortedByTime().size(), level.getTotalQuantity());

                if (aggressorOrder.getType() == OrderType.LIMIT) {
                    if (aggressorOrder.getDirection() == OrderDirection.BUY && levelPrice.compareTo(aggressorOrder.getPrice()) > 0) {
                        log.debug("MATCHING: BUY limit price {} < ask level {}, stopping", aggressorOrder.getPrice(), levelPrice);
                        break;
                    }
                    if (aggressorOrder.getDirection() == OrderDirection.SELL && levelPrice.compareTo(aggressorOrder.getPrice()) < 0) {
                        log.debug("MATCHING: SELL limit price {} > bid level {}, stopping", aggressorOrder.getPrice(), levelPrice);
                        break;
                    }
                }

                long quantityToExecute = Math.min(aggressorOrder.getRemainingQuantity(), level.getTotalQuantity());

                if (quantityToExecute > 0) {
                    log.debug("MATCHING: Executing {} lots at price level {}", quantityToExecute, levelPrice);
                    List<Trade> levelTrades = executeProRataOnLevel(level, quantityToExecute, levelPrice, aggressorOrder);
                    trades.addAll(levelTrades);
                    if (!levelTrades.isEmpty()) {
                        bookChanged = true;
                    }
                }

                if (level.isEmpty()) {
                    log.debug("MATCHING: Price level {} is now empty, removing", levelPrice);
                    levelIterator.remove();
                    bookChanged = true;
                }
            }

            if (bookChanged) {
                orderBookManager.notifyUpdate();
            }

            log.info("MATCHING: Order {} execution completed: {} trades, remaining qty={}, status={}",
                    aggressorOrder.getId(),
                    trades.size(),
                    aggressorOrder.getRemainingQuantity(),
                    aggressorOrder.getStatus());

            return trades;
        } finally {
            orderBookManager.getLock().writeLock().unlock();
        }
    }

    private NavigableMap<BigDecimal, PriceLevel> getOppositeSide(OrderDirection direction) {
        return direction == OrderDirection.BUY ? orderBookManager.getAsks() : orderBookManager.getBids();
    }

    private List<Trade> executeProRataOnLevel(PriceLevel level, long quantityToExecute, BigDecimal price, Order aggressorOrder) {
        List<Trade> trades = new ArrayList<>();
        long totalOnLevel = level.getTotalQuantity();
        long distributed = 0;

        List<Order> ordersOnLevel = level.getOrdersSortedByTime();
        Map<Order, Long> allocations = new LinkedHashMap<>();

        log.debug("MATCHING: Pro-Rata allocation: {} lots across {} passive orders (total on level={})",
                quantityToExecute, ordersOnLevel.size(), totalOnLevel);

        // Step 1: Pro-Rata
        for (Order passiveOrder : ordersOnLevel) {
            double share = (double) quantityToExecute * passiveOrder.getRemainingQuantity() / totalOnLevel;
            long proRataShare = (long) Math.floor(share);

            allocations.put(passiveOrder, proRataShare);
            distributed += proRataShare;
            log.trace("MATCHING: Pro-Rata order {} [{}]: share={:.2f}, allocated={}",
                    passiveOrder.getId(), passiveOrder.getSource(), share, proRataShare);
        }

        // Step 2: FIFO Tail
        long tail = quantityToExecute - distributed;
        if (tail > 0) {
            log.debug("MATCHING: FIFO tail distribution: {} lots remaining after pro-rata", tail);
        }
        while (tail > 0) {
            for (Order passiveOrder : ordersOnLevel) {
                if (tail <= 0) break;

                long currentAlloc = allocations.get(passiveOrder);
                long maxCanAdd = passiveOrder.getRemainingQuantity() - currentAlloc;

                if (maxCanAdd > 0) {
                    allocations.put(passiveOrder, currentAlloc + 1);
                    tail--;
                    log.trace("MATCHING: FIFO +1 to order {}, new alloc={}", passiveOrder.getId(), currentAlloc + 1);
                }
            }
        }

        // Step 3: Create Trades and Update Orders
        for (Map.Entry<Order, Long> alloc : allocations.entrySet()) {
            Order passiveOrder = alloc.getKey();
            long quantity = alloc.getValue();

            if (quantity > 0) {
                Trade trade = Trade.builder()
                        .id(UUID.randomUUID())
                        .aggressorOrderId(aggressorOrder.getId())
                        .aggressorOrderSource(aggressorOrder.getSource())
                        .aggressorDirection(aggressorOrder.getDirection())
                        .passiveOrderId(passiveOrder.getId())
                        .passiveAccountId(passiveOrder.getAccountId())
                        .passiveOrderSource(passiveOrder.getSource())
                        .instrumentId(aggressorOrder.getInstrumentId())
                        .price(price)
                        .quantity(quantity)
                        .build();
                trades.add(trade);

                log.info("TRADE: {} | aggressor={} [{}] vs passive={} [{}] | {} @ {} | qty={}",
                        trade.getId(),
                        aggressorOrder.getId(), aggressorOrder.getSource(),
                        passiveOrder.getId(), passiveOrder.getSource(),
                        aggressorOrder.getDirection(), price, quantity);

                eventPublisher.publishEvent(new TradeExecutedEvent(this, trade));

                passiveOrder.fill(quantity);
                eventPublisher.publishEvent(new OrderStateChangedEvent(this, passiveOrder));

                aggressorOrder.fill(quantity);
                eventPublisher.publishEvent(new OrderStateChangedEvent(this, aggressorOrder));

                if (passiveOrder.isFullyFilled()) {
                    log.debug("MATCHING: Passive order {} fully filled, removing from book", passiveOrder.getId());
                    level.removeOrder(passiveOrder);
                    orderBookManager.removeOrderIndex(passiveOrder.getId());
                }
            }
        }

        return trades;
    }
}