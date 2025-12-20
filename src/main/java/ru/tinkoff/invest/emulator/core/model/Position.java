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
        long newQuantity = this.quantity + quantityDelta;

        // Проверяем пересечение нуля (лонг → шорт или шорт → лонг)
        boolean crossingZero = (this.quantity > 0 && newQuantity < 0) || (this.quantity < 0 && newQuantity > 0);

        if (crossingZero) {
            // Позиция перевернулась — новая средняя = цена исполнения
            this.averagePrice = executionPrice;
        } else if (newQuantity == 0) {
            // Позиция закрыта
            this.averagePrice = BigDecimal.ZERO;
        } else if ((quantityDelta > 0 && this.quantity >= 0) || (quantityDelta < 0 && this.quantity <= 0)) {
            // Добавление к существующей позиции — пересчитываем среднюю
            // Для лонга: покупаем ещё
            // Для шорта: продаём ещё (увеличиваем шорт)
            BigDecimal oldValue = this.averagePrice.multiply(BigDecimal.valueOf(Math.abs(this.quantity)));
            BigDecimal newValue = executionPrice.multiply(BigDecimal.valueOf(Math.abs(quantityDelta)));
            this.averagePrice = oldValue.add(newValue).divide(BigDecimal.valueOf(Math.abs(newQuantity)), 9, RoundingMode.HALF_UP);
        }
        // При частичном закрытии позиции (уменьшении) — средняя не меняется

        this.quantity = newQuantity;
    }
}
