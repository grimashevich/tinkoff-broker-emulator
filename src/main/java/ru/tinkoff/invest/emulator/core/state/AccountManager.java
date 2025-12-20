package ru.tinkoff.invest.emulator.core.state;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.tinkoff.invest.emulator.config.EmulatorProperties;
import ru.tinkoff.invest.emulator.core.model.Account;
import ru.tinkoff.invest.emulator.core.model.Position;
import ru.tinkoff.invest.emulator.core.model.Trade;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.List;

import ru.tinkoff.invest.emulator.core.event.TradeExecutedEvent;
import ru.tinkoff.invest.emulator.core.model.OrderDirection;
import ru.tinkoff.invest.emulator.core.model.OrderSource;
import org.springframework.context.event.EventListener;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountManager {
    private final EmulatorProperties properties;
    private Account account;

    @PostConstruct
    public void init() {
        account = new Account(
                properties.getAccount().getId(),
                properties.getAccount().getInitialBalance()
        );
        log.info("Initialized account {} with balance {}", account.getId(), account.getBalance());
    }

    @EventListener
    public void onTradeExecuted(TradeExecutedEvent event) {
        Trade trade = event.getTrade();

        // Обновляем аккаунт только если одна из сторон сделки — заявка бота (API)
        if (trade.getAggressorOrderSource() == OrderSource.API) {
            boolean isBuy = trade.getAggressorDirection() == OrderDirection.BUY;
            updateState(trade.getInstrumentId(), trade.getQuantity(), trade.getPrice(), isBuy);
        }

        if (trade.getPassiveOrderSource() == OrderSource.API) {
            // Пассивная сторона — противоположное направление от агрессора
            boolean isBuy = trade.getAggressorDirection() != OrderDirection.BUY;
            updateState(trade.getInstrumentId(), trade.getQuantity(), trade.getPrice(), isBuy);
        }
    }

    public Account getAccount() {
        return account;
    }

    public synchronized void processTrade(Trade trade) {
        // Assume we are always the aggressor or the passive side?
        // This emulator is single user (the bot).
        // If the trade involves our order, we update.
        // But in this emulator, 'Orders from API' are the bot's orders.
        // 'Orders from Admin' are "Market".
        // We need to know if the trade belongs to the Account.
        // Our Order model doesn't store AccountId explicitly (it does in proto, but core model maybe didn't?)
        // Wait, core model Order has `source`.
        // If source is API, it's our account.
        // But `Trade` has `aggressorOrderId` and `passiveOrderId`.
        // We need to look up orders or assume.
        // Actually, the prompt says "Client application ... should not see difference".
        // The emulator simulates the broker for ONE account (configured in yml).
        // So any order coming via gRPC is for THIS account.
        // Any trade generated where one of the orders is from gRPC (API) affects the account.
        
        // However, I don't have access to Order source inside processTrade easily unless I look up the order.
        // But the matching engine returns Trades.
        // The calling service (OrdersService) knows which order was ours (the aggressor usually).
        // But what if we were passive? (Limit order sitting in book).
        // We need to handle updates when our passive order matches.
        
        // For now, let's assume `OrdersService` calls `processTrade` when it receives trades from MatchingEngine?
        // No, MatchingEngine returns trades.
        // If we placed a Limit Order and it went to book, later someone (Admin) places order and matches us.
        // We need a way to listen to trades.
        // The roadmap mentions "Event System" in Stage 3.2.
        // For Stage 3.1, we mostly care about `PostOrder` immediate execution.
        // But `getPortfolio` relies on correct state.
        
        // I'll add a method `updateState(direction, price, quantity, commission)`
        // For simple emulation, commission can be 0 or calculated.
        
        // Let's implement `updatePosition` which updates balance and position.
    }
    
    // Simplification: We update state explicitly when we know we traded.
    // For Stage 3.1, I will implement update logic that can be called.
    
    public synchronized void updateState(String instrumentId, long quantityDelta, BigDecimal price, boolean isBuy) {
        // QuantityDelta is signed? Or we use isBuy?
        // Let's say quantityDelta is absolute.
        
        BigDecimal cost = price.multiply(BigDecimal.valueOf(quantityDelta));
        
        if (isBuy) {
            account.setBalance(account.getBalance().subtract(cost));
            account.getPosition(instrumentId).update(quantityDelta, price);
        } else {
            account.setBalance(account.getBalance().add(cost));
            account.getPosition(instrumentId).update(-quantityDelta, price);
        }
        
        log.info("Updated account state: Balance={}, Position={}", account.getBalance(), account.getPosition(instrumentId));
    }

    public BigDecimal getPortfolioValue(BigDecimal currentPrice) {
        BigDecimal cash = account.getBalance();
        BigDecimal positionsValue = account.getPositions().values().stream()
                .map(p -> {
                    // Use current price if provided, else use last known or average (fallback)
                    BigDecimal price = currentPrice != null ? currentPrice : p.getAveragePrice(); 
                    return price.multiply(BigDecimal.valueOf(p.getQuantity()));
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return cash.add(positionsValue);
    }

    public long getMaxLots(boolean isBuy, BigDecimal currentPrice, BigDecimal instrumentPrice) {
        if (instrumentPrice == null || instrumentPrice.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("getMaxLots: instrumentPrice is null or zero, returning 0");
            return 0;
        }

        BigDecimal portfolioValue = getPortfolioValue(instrumentPrice);
        log.debug("getMaxLots: isBuy={}, instrumentPrice={}, portfolioValue={}", isBuy, instrumentPrice, portfolioValue);

        if (isBuy) {
            BigDecimal marginMult = properties.getAccount().getMarginMultiplierBuy();
            log.debug("getMaxLots BUY: marginMultiplierBuy={}", marginMult);
            if (marginMult == null) {
                log.warn("getMaxLots BUY: marginMultiplierBuy is null!");
                return 0;
            }
            // floor(portfolioValue * marginMultiplierBuy / currentAskPrice)
            BigDecimal buyingPower = portfolioValue.multiply(marginMult);
            return buyingPower.divide(instrumentPrice, 0, java.math.RoundingMode.FLOOR).longValue();
        } else {
             BigDecimal marginMult = properties.getAccount().getMarginMultiplierSell();
             log.debug("getMaxLots SELL: marginMultiplierSell={}", marginMult);
             if (marginMult == null) {
                 log.warn("getMaxLots SELL: marginMultiplierSell is null!");
                 return 0;
             }
             // floor(portfolioValue * marginMultiplierSell / currentBidPrice)
             BigDecimal sellingPower = portfolioValue.multiply(marginMult);
             long maxSell = sellingPower.divide(instrumentPrice, 0, java.math.RoundingMode.FLOOR).longValue();

             // + current position?
             // If we are Long 10, can we sell 10 + maxShort?
             // Usually yes.
             Position pos = account.getPosition(properties.getInstrument().getUid()); // Assuming single instrument for now as per config
             if (pos != null && pos.getQuantity() > 0) {
                 maxSell += pos.getQuantity();
             }
             log.debug("getMaxLots SELL: sellingPower={}, maxSell={}", sellingPower, maxSell);
             return maxSell;
        }
    }
}
