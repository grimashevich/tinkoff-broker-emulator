package ru.tinkoff.invest.emulator.core.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import ru.tinkoff.invest.emulator.core.model.Trade;

@Getter
public class TradeExecutedEvent extends ApplicationEvent {
    private final Trade trade;

    public TradeExecutedEvent(Object source, Trade trade) {
        super(source);
        this.trade = trade;
    }
}
