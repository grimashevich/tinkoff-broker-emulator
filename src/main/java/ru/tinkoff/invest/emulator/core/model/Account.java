package ru.tinkoff.invest.emulator.core.model;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Data
public class Account {
    private final String id;
    private BigDecimal balance;
    private final Map<String, Position> positions = new ConcurrentHashMap<>();

    public Account(String id, BigDecimal initialBalance) {
        this.id = id;
        this.balance = initialBalance;
    }

    public Position getPosition(String instrumentId) {
        return positions.computeIfAbsent(instrumentId, k -> Position.builder()
                .instrumentId(k)
                .quantity(0)
                .averagePrice(BigDecimal.ZERO)
                .currentPrice(BigDecimal.ZERO)
                .build());
    }
}
