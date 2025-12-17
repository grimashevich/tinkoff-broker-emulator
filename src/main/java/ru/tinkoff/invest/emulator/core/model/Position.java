package ru.tinkoff.invest.emulator.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Position {
    private String instrumentId;
    private long quantity;
    private BigDecimal averagePrice;
    private BigDecimal currentPrice; // Last known price for valuation

    public void update(long quantityDelta, BigDecimal executionPrice) {
        if (quantityDelta > 0) {
            // Покупка — пересчитываем среднюю
            long newQuantity = this.quantity + quantityDelta;
            if (newQuantity > 0) {
                BigDecimal totalCost = this.averagePrice.multiply(BigDecimal.valueOf(this.quantity))
                        .add(executionPrice.multiply(BigDecimal.valueOf(quantityDelta)));
                this.averagePrice = totalCost.divide(BigDecimal.valueOf(newQuantity), 9, RoundingMode.HALF_UP);
            }
            this.quantity = newQuantity;
        } else {
            // Продажа — просто уменьшаем количество, среднюю не меняем
            this.quantity += quantityDelta;
            if (this.quantity <= 0) {
                this.quantity = 0;
                this.averagePrice = BigDecimal.ZERO;
            }
        }
    }
}
