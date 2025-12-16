package ru.tinkoff.invest.emulator.web.dto;

import lombok.Builder;
import lombok.Data;
import ru.tinkoff.invest.emulator.core.model.OrderDirection;
import ru.tinkoff.invest.emulator.core.model.OrderType;

import java.math.BigDecimal;

@Data
@Builder
public class OrderDto {
    private String id;
    private String instrumentId;
    private String accountId;
    private OrderDirection direction;
    private OrderType type;
    private BigDecimal price;
    private long quantity;
    private long filledQuantity;
    private String status;
    private String source;
}
