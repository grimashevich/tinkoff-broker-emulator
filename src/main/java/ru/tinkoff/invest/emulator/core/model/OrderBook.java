package ru.tinkoff.invest.emulator.core.model;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.NavigableMap;
import java.util.TreeMap;

@Data
public class OrderBook {
    private final String instrumentId;
    // Price -> PriceLevel
    // Bids: High to Low (Descending)
    // Asks: Low to High (Ascending)
    // But TreeMap natural order is Ascending.
    // So for Bids we might want `descendingMap()` view when matching, but storage can be standard.
    private final NavigableMap<BigDecimal, PriceLevel> bids = new TreeMap<>(Collections.reverseOrder());
    private final NavigableMap<BigDecimal, PriceLevel> asks = new TreeMap<>();

    public OrderBook(String instrumentId) {
        this.instrumentId = instrumentId;
    }
}
