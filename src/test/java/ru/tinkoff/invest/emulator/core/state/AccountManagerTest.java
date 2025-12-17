package ru.tinkoff.invest.emulator.core.state;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.tinkoff.invest.emulator.config.EmulatorProperties;
import ru.tinkoff.invest.emulator.core.event.TradeExecutedEvent;
import ru.tinkoff.invest.emulator.core.model.OrderDirection;
import ru.tinkoff.invest.emulator.core.model.OrderSource;
import ru.tinkoff.invest.emulator.core.model.Trade;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountManagerTest {

    private static final String ACCOUNT_ID = "test-account";
    private static final String INSTRUMENT_ID = "test-instrument";
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("200000");

    @Mock
    private EmulatorProperties properties;
    @Mock
    private EmulatorProperties.Account accountProperties;

    private AccountManager accountManager;

    @BeforeEach
    void setUp() {
        when(properties.getAccount()).thenReturn(accountProperties);
        when(accountProperties.getId()).thenReturn(ACCOUNT_ID);
        when(accountProperties.getInitialBalance()).thenReturn(INITIAL_BALANCE);

        accountManager = new AccountManager(properties);
        accountManager.init();
    }

    @Test
    void onTradeExecuted_Buy_ApiAggressor_ShouldUpdateBalanceAndPosition() {
        // Given
        BigDecimal price = new BigDecimal("100.50");
        long quantity = 10;
        Trade trade = Trade.builder()
                .id(UUID.randomUUID())
                .instrumentId(INSTRUMENT_ID)
                .aggressorOrderSource(OrderSource.API)
                .aggressorDirection(OrderDirection.BUY)
                .passiveOrderSource(OrderSource.ADMIN_PANEL)
                .price(price)
                .quantity(quantity)
                .build();
        TradeExecutedEvent event = new TradeExecutedEvent(this, trade);

        BigDecimal expectedBalance = INITIAL_BALANCE.subtract(price.multiply(BigDecimal.valueOf(quantity))); // 200000 - 1005 = 198995
        long expectedPositionQuantity = 10;

        // When
        accountManager.onTradeExecuted(event);

        // Then
        assertEquals(0, expectedBalance.compareTo(accountManager.getAccount().getBalance()));
        assertEquals(expectedPositionQuantity, accountManager.getAccount().getPosition(INSTRUMENT_ID).getQuantity());
    }

    @Test
    void onTradeExecuted_Sell_ApiPassive_ShouldUpdateBalanceAndPosition() {
        // Given: First, a buy trade to have a position
        BigDecimal buyPrice = new BigDecimal("90");
        long buyQuantity = 20;
        accountManager.updateState(INSTRUMENT_ID, buyQuantity, buyPrice, true);

        // Now, the actual test case: a sell trade
        BigDecimal sellPrice = new BigDecimal("110.00");
        long sellQuantity = 5;
        Trade trade = Trade.builder()
                .id(UUID.randomUUID())
                .instrumentId(INSTRUMENT_ID)
                .aggressorOrderSource(OrderSource.ADMIN_PANEL)
                .aggressorDirection(OrderDirection.BUY) // Aggressor buys, so passive (API) sells
                .passiveOrderSource(OrderSource.API)
                .price(sellPrice)
                .quantity(sellQuantity)
                .build();
        TradeExecutedEvent event = new TradeExecutedEvent(this, trade);

        BigDecimal balanceAfterBuy = INITIAL_BALANCE.subtract(buyPrice.multiply(BigDecimal.valueOf(buyQuantity)));
        BigDecimal expectedBalance = balanceAfterBuy.add(sellPrice.multiply(BigDecimal.valueOf(sellQuantity))); // 198200 + 550 = 198750
        long expectedPositionQuantity = buyQuantity - sellQuantity; // 20 - 5 = 15

        // When
        accountManager.onTradeExecuted(event);

        // Then
        assertEquals(0, expectedBalance.compareTo(accountManager.getAccount().getBalance()));
        assertEquals(expectedPositionQuantity, accountManager.getAccount().getPosition(INSTRUMENT_ID).getQuantity());
    }

    @Test
    void onTradeExecuted_NonApiTrade_ShouldNotChangeAccount() {
        // Given
        Trade trade = Trade.builder()
                .id(UUID.randomUUID())
                .instrumentId(INSTRUMENT_ID)
                .aggressorOrderSource(OrderSource.ADMIN_PANEL)
                .aggressorDirection(OrderDirection.BUY)
                .passiveOrderSource(OrderSource.ADMIN_PANEL)
                .price(new BigDecimal("100"))
                .quantity(10)
                .build();
        TradeExecutedEvent event = new TradeExecutedEvent(this, trade);

        // When
        accountManager.onTradeExecuted(event);

        // Then
        assertEquals(0, INITIAL_BALANCE.compareTo(accountManager.getAccount().getBalance()));
        assertEquals(0, accountManager.getAccount().getPosition(INSTRUMENT_ID).getQuantity());
    }
}
