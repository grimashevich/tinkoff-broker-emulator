package ru.tinkoff.invest.emulator.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.tinkoff.invest.emulator.core.model.OrderDirection;
import ru.tinkoff.invest.emulator.core.model.OrderType;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateOrderRequest {
    private String instrumentId; // Optional, defaults to config if missing
    private OrderDirection direction;
    private OrderType orderType;
    private BigDecimal price;
    private long quantity;
    private String accountId; // Optional, defaults to "admin"
}
