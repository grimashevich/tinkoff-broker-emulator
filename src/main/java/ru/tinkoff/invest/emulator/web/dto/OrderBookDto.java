package ru.tinkoff.invest.emulator.web.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@Builder
public class OrderBookDto {
    private String instrumentId;
    private int depth;
    private List<PriceLevelDto> bids;
    private List<PriceLevelDto> asks;
    private Instant timestamp;

    @Data
    @Builder
    public static class PriceLevelDto {
        private BigDecimal price;
        private long quantity;
        private int ordersCount;
        private long apiQuantity;  // Объём заявок бота (OrderSource.API)
    }
}
