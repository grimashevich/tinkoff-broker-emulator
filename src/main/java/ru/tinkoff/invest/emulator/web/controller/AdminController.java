package ru.tinkoff.invest.emulator.web.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.tinkoff.invest.emulator.config.EmulatorProperties;
import ru.tinkoff.invest.emulator.core.matching.ProRataMatchingEngine;
import ru.tinkoff.invest.emulator.core.model.*;
import ru.tinkoff.invest.emulator.core.orderbook.OrderBookManager;
import ru.tinkoff.invest.emulator.core.state.AccountManager;
import ru.tinkoff.invest.emulator.web.dto.CreateOrderRequest;
import ru.tinkoff.invest.emulator.web.dto.OrderBookDto;
import ru.tinkoff.invest.emulator.web.dto.OrderDto;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AdminController {

    private final OrderBookManager orderBookManager;
    private final ProRataMatchingEngine matchingEngine;
    private final AccountManager accountManager;
    private final EmulatorProperties properties;

    @GetMapping("/orderbook")
    public OrderBookDto getOrderBook() {
        OrderBook book = orderBookManager.getSnapshot(20);
        return OrderBookDto.builder()
                .instrumentId(book.getInstrumentId())
                .depth(20)
                .timestamp(Instant.now())
                .bids(book.getBids().entrySet().stream()
                        .map(e -> OrderBookDto.PriceLevelDto.builder()
                                .price(e.getKey())
                                .quantity(e.getValue().getTotalQuantity())
                                .ordersCount(e.getValue().getOrders().size())
                                .build())
                        .collect(Collectors.toList()))
                .asks(book.getAsks().entrySet().stream()
                        .map(e -> OrderBookDto.PriceLevelDto.builder()
                                .price(e.getKey())
                                .quantity(e.getValue().getTotalQuantity())
                                .ordersCount(e.getValue().getOrders().size())
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }

    @GetMapping("/orders")
    public List<OrderDto> getOrders() {
        // Return all orders from all accounts for Admin
        // OrderBookManager has `getOrders(accountId)`.
        // We probably need `getAllOrders()`?
        // Or we can iterate over orderIndex if we expose it (unlikely).
        // Let's rely on account-based retrieval for now or assume single instrument and fetch from book + internal tracking?
        // `OrderBookManager` only keeps active orders in the book.
        // If we want history, we need a separate OrderRepository.
        // For active orders, we can get active orders from Book.
        // `OrderBookManager` has `orderIndex`.
        // I should expose `getAllOrders` in `OrderBookManager` for Admin.
        
        return orderBookManager.getAllOrders().stream()
                .map(this::mapOrder)
                .sorted(Comparator.comparing(OrderDto::getId)) // Stability
                .collect(Collectors.toList());
    }

    @PostMapping("/orders")
    public OrderDto createOrder(@RequestBody CreateOrderRequest request) {
        String instrumentId = request.getInstrumentId() != null ? request.getInstrumentId() : properties.getInstrument().getUid();
        String accountId = request.getAccountId() != null ? request.getAccountId() : "admin-market-maker";
        
        Order order = Order.builder()
                .id(UUID.randomUUID())
                .instrumentId(instrumentId)
                .accountId(accountId)
                .direction(request.getDirection())
                .type(request.getOrderType())
                .price(request.getPrice())
                .quantity(request.getQuantity())
                .source(OrderSource.ADMIN_PANEL)
                .build();

        // Admin orders also go through matching engine to execute against Bot orders
        // Need to publish NEW event? The matching engine logic I updated earlier handles execution events.
        // But initial creation? 
        // OrdersServiceImpl publishes NEW. AdminController should too for consistency if we use event stream.
        // But for MVP admin panel, maybe not critical.
        
        List<Trade> trades = matchingEngine.executeOrder(order);
        
        // Update Admin/MarketMaker account state? 
        // We can, but mostly we care about the Bot's account being updated if it matched.
        // The Bot's account is updated in `OrdersServiceImpl` logic if Bot is aggressor.
        // If Admin is aggressor and Bot is passive, who updates Bot's account?
        // `OrdersServiceImpl` logic:
        // "Update account for Passive order if it belongs to the Bot (API)"
        // Wait, that logic was inside `OrdersServiceImpl.postOrder`.
        // `AdminController` calls `MatchingEngine` DIRECTLY.
        // So `OrdersServiceImpl` logic is NOT executed!
        // We need to duplicate the "Update Passive Bot Order" logic here or extract it.
        // IMPORTANT: If Admin hits a passive Bot order, Bot's account MUST be updated.
        
        for (Trade trade : trades) {
             if (trade.getPassiveOrderSource() == OrderSource.API) {
                 boolean isAggressorBuy = order.getDirection() == OrderDirection.BUY;
                 boolean isPassiveBuy = !isAggressorBuy;
                 accountManager.updateState(trade.getInstrumentId(), trade.getQuantity(), trade.getPrice(), isPassiveBuy);
             }
        }
        
        // If Limit and remainder, add to book
        if (order.getType() == OrderType.LIMIT && !order.isFullyFilled()) {
            orderBookManager.addOrder(order);
        }
        
        return mapOrder(order);
    }

    @DeleteMapping("/orders/{id}")
    public ResponseEntity<Void> cancelOrder(@PathVariable String id) {
        boolean removed = orderBookManager.removeOrder(UUID.fromString(id));
        if (removed) {
            return ResponseEntity.ok().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/account")
    public Account getAccount() {
        return accountManager.getAccount();
    }

    private OrderDto mapOrder(Order order) {
        return OrderDto.builder()
                .id(order.getId().toString())
                .instrumentId(order.getInstrumentId())
                .accountId(order.getAccountId())
                .direction(order.getDirection())
                .type(order.getType())
                .price(order.getPrice())
                .quantity(order.getQuantity())
                .filledQuantity(order.getFilledQuantity())
                .status(order.getStatus().name())
                .source(order.getSource() != null ? order.getSource().name() : "UNKNOWN")
                .build();
    }
}
