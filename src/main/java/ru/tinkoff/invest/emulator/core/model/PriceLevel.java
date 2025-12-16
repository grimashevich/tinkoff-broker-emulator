package ru.tinkoff.invest.emulator.core.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class PriceLevel {
    private BigDecimal price;
    private final List<Order> orders = new ArrayList<>();

    public PriceLevel(BigDecimal price) {
        this.price = price;
    }

    public void addOrder(Order order) {
        orders.add(order);
    }

    public boolean removeOrder(Order order) {
        return orders.remove(order);
    }

    public boolean isEmpty() {
        return orders.isEmpty();
    }

    public long getTotalQuantity() {
        return orders.stream()
                .mapToLong(Order::getRemainingQuantity)
                .sum();
    }
    
    public List<Order> getOrdersSortedByTime() {
         // Assuming insertion order is time order for now, as we append to end of list.
         // If we need strict time sorting, we can sort, but ArrayList maintains insertion order.
         return new ArrayList<>(orders);
    }
}
