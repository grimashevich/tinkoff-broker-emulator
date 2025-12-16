package ru.tinkoff.invest.emulator.core.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import ru.tinkoff.invest.emulator.core.model.Order;

@Getter
public class OrderStateChangedEvent extends ApplicationEvent {
    private final Order order;

    public OrderStateChangedEvent(Object source, Order order) {
        super(source);
        this.order = order;
    }
}
