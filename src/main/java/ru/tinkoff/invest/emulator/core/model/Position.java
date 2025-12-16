package ru.tinkoff.invest.emulator.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Position {
    private String instrumentId;
    private long quantity;
    private BigDecimal averagePrice;
    private BigDecimal currentPrice; // Last known price for valuation
    
    public void update(long quantityDelta, BigDecimal price) {
        if (quantityDelta == 0) return;

        BigDecimal totalValue = averagePrice.multiply(BigDecimal.valueOf(Math.abs(quantity)));
        BigDecimal tradeValue = price.multiply(BigDecimal.valueOf(Math.abs(quantityDelta)));
        
        long newQuantity = quantity + quantityDelta;
        
        if (newQuantity == 0) {
            this.quantity = 0;
            this.averagePrice = BigDecimal.ZERO;
            return;
        }

        // Simple average price update logic
        // If direction is same (increasing position), update average
        // If direction is opposite (decreasing position), average stays same until flipped
        
        boolean sameDirection = (quantity >= 0 && quantityDelta > 0) || (quantity <= 0 && quantityDelta < 0);
        
        if (sameDirection) {
            BigDecimal newTotalValue = totalValue.add(tradeValue);
            this.averagePrice = newTotalValue.divide(BigDecimal.valueOf(Math.abs(newQuantity)), 9, java.math.RoundingMode.HALF_UP);
        }
        // If reducing position, average price doesn't change usually (FIFO/Weighted logic). 
        // We stick to simple logic: average price is entry price.
        // If we flip (e.g. Long 10 -> Short 5), the remaining 5 Short will have new price?
        // Let's implement simple Weighted Average for increasing, and keep for decreasing.
        // What if we cross zero?
        // e.g. Long 10 @ 100. Sell 15 @ 110. Result: Short 5 @ 110.
        
        if ((quantity > 0 && newQuantity < 0) || (quantity < 0 && newQuantity > 0)) {
            // Crossed zero
            this.averagePrice = price;
        }
        
        this.quantity = newQuantity;
    }
}
