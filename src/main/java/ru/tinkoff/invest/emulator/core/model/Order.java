package ru.tinkoff.invest.emulator.core.model;

import lombok.Builder;
import lombok.Data;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@ToString
public class Order {
    private final UUID id;
    private final String instrumentId;
    private final String accountId;
    private final OrderDirection direction;
    private final OrderType type;
    private final BigDecimal price;
    private final long quantity; // Initial quantity
    @Builder.Default
    private long filledQuantity = 0;
    @Builder.Default
    private OrderStatus status = OrderStatus.NEW;
    @Builder.Default
    private final Instant createdAt = Instant.now();
    private final OrderSource source;

    public long getRemainingQuantity() {
        return quantity - filledQuantity;
    }

    public void fill(long amount) {
        if (amount <= 0) {
            return;
        }
        this.filledQuantity += amount;
        if (filledQuantity >= quantity) {
            this.status = OrderStatus.FILLED;
            this.filledQuantity = quantity; // Clamp just in case
        } else {
            this.status = OrderStatus.PARTIALLY_FILLED;
        }
    }
    
    public boolean isFullyFilled() {
        return filledQuantity >= quantity;
    }
}
