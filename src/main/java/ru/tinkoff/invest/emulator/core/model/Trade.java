package ru.tinkoff.invest.emulator.core.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class Trade {
    private final UUID id;
    private final UUID aggressorOrderId;
    private final UUID passiveOrderId; // Can be null if it's some other mechanism, but usually matching involves two orders
    private final String passiveAccountId;
    private final OrderSource passiveOrderSource;
    private final String instrumentId;
    private final BigDecimal price;
    private final long quantity;
    @Builder.Default
    private final Instant timestamp = Instant.now();
}
