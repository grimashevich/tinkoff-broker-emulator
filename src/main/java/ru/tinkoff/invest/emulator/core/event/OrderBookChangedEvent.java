package ru.tinkoff.invest.emulator.core.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import ru.tinkoff.invest.emulator.core.model.OrderBook;

@Getter
public class OrderBookChangedEvent extends ApplicationEvent {
    private final OrderBook orderBook;

    public OrderBookChangedEvent(Object source, OrderBook orderBook) {
        super(source);
        this.orderBook = orderBook;
    }
}
