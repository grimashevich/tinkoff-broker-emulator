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
        orderBookManager.getLock().writeLock().lock();
        boolean bookChanged = false;
        try {
            if (aggressorOrder.getQuantity() <= 0) {
                return Collections.emptyList();
            }

            List<Trade> trades = new ArrayList<>();
            NavigableMap<BigDecimal, PriceLevel> oppositeSide = getOppositeSide(aggressorOrder.getDirection());
            
            Iterator<Map.Entry<BigDecimal, PriceLevel>> levelIterator = oppositeSide.entrySet().iterator();

            while (aggressorOrder.getRemainingQuantity() > 0 && levelIterator.hasNext()) {
                Map.Entry<BigDecimal, PriceLevel> entry = levelIterator.next();
                BigDecimal levelPrice = entry.getKey();
                PriceLevel level = entry.getValue();

                if (aggressorOrder.getType() == OrderType.LIMIT) {
                    if (aggressorOrder.getDirection() == OrderDirection.BUY && levelPrice.compareTo(aggressorOrder.getPrice()) > 0) {
                        break;
                    }
                    if (aggressorOrder.getDirection() == OrderDirection.SELL && levelPrice.compareTo(aggressorOrder.getPrice()) < 0) {
                        break;
                    }
                }

                long quantityToExecute = Math.min(aggressorOrder.getRemainingQuantity(), level.getTotalQuantity());

                if (quantityToExecute > 0) {
                    List<Trade> levelTrades = executeProRataOnLevel(level, quantityToExecute, levelPrice, aggressorOrder);
                    trades.addAll(levelTrades);
                    if (!levelTrades.isEmpty()) {
                        bookChanged = true;
                    }
                }

                if (level.isEmpty()) {
                    levelIterator.remove();
                    bookChanged = true;
                }
            }
            
            if (bookChanged) {
                orderBookManager.notifyUpdate();
            }

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

        // Step 1: Pro-Rata
        for (Order passiveOrder : ordersOnLevel) {
            double share = (double) quantityToExecute * passiveOrder.getRemainingQuantity() / totalOnLevel;
            long proRataShare = (long) Math.floor(share);
            
            allocations.put(passiveOrder, proRataShare);
            distributed += proRataShare;
        }

        // Step 2: FIFO Tail
        long tail = quantityToExecute - distributed;
        while (tail > 0) {
            for (Order passiveOrder : ordersOnLevel) {
                if (tail <= 0) break;

                long currentAlloc = allocations.get(passiveOrder);
                long maxCanAdd = passiveOrder.getRemainingQuantity() - currentAlloc;

                if (maxCanAdd > 0) {
                    allocations.put(passiveOrder, currentAlloc + 1);
                    tail--;
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
                        .passiveOrderId(passiveOrder.getId())
                        .passiveAccountId(passiveOrder.getAccountId())
                        .passiveOrderSource(passiveOrder.getSource())
                        .instrumentId(aggressorOrder.getInstrumentId())
                        .price(price)
                        .quantity(quantity)
                        .build();
                trades.add(trade);
                eventPublisher.publishEvent(new TradeExecutedEvent(this, trade));

                passiveOrder.fill(quantity);
                eventPublisher.publishEvent(new OrderStateChangedEvent(this, passiveOrder));

                aggressorOrder.fill(quantity);
                // Aggressor event usually handled by caller or here?
                // Caller (PostOrder) handles aggressor logic. But better to fire event here to be consistent.
                eventPublisher.publishEvent(new OrderStateChangedEvent(this, aggressorOrder));

                if (passiveOrder.isFullyFilled()) {
                    level.removeOrder(passiveOrder);
                    orderBookManager.removeOrderIndex(passiveOrder.getId());
                }
            }
        }

        return trades;
    }
}