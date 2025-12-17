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
        log.debug("REST GetOrderBook: Fetching orderbook snapshot");
        OrderBook book = orderBookManager.getSnapshot(20);
        log.debug("REST GetOrderBook: bids={} levels, asks={} levels",
                book.getBids().size(), book.getAsks().size());
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
        log.debug("REST GetOrders: Fetching all active orders");
        List<Order> orders = orderBookManager.getAllOrders();
        log.debug("REST GetOrders: Found {} active orders", orders.size());

        return orders.stream()
                .map(this::mapOrder)
                .sorted(Comparator.comparing(OrderDto::getId)) // Stability
                .collect(Collectors.toList());
    }

    @PostMapping("/orders")
    public OrderDto createOrder(@RequestBody CreateOrderRequest request) {
        log.info("REST CreateOrder [ADMIN_PANEL]: {} {} @ {} qty={}",
                request.getDirection(), request.getOrderType(), request.getPrice(), request.getQuantity());

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

        log.debug("REST CreateOrder: Created order {} for account {}", order.getId(), accountId);

        List<Trade> trades = matchingEngine.executeOrder(order);

        log.info("REST CreateOrder: Order {} executed with {} trades, remaining={}",
                order.getId(), trades.size(), order.getRemainingQuantity());

        // Update Bot account if Admin hit passive Bot orders
        for (Trade trade : trades) {
             if (trade.getPassiveOrderSource() == OrderSource.API) {
                 boolean isAggressorBuy = order.getDirection() == OrderDirection.BUY;
                 boolean isPassiveBuy = !isAggressorBuy;
                 log.debug("REST CreateOrder: Updating Bot account for passive fill: {} lots @ {}",
                         trade.getQuantity(), trade.getPrice());
                 accountManager.updateState(trade.getInstrumentId(), trade.getQuantity(), trade.getPrice(), isPassiveBuy);
             }
        }

        // If Limit and remainder, add to book
        if (order.getType() == OrderType.LIMIT && !order.isFullyFilled()) {
            log.debug("REST CreateOrder: Adding remainder {} lots to orderbook at {}",
                    order.getRemainingQuantity(), order.getPrice());
            orderBookManager.addOrder(order);
        }

        return mapOrder(order);
    }

    @DeleteMapping("/orders/{id}")
    public ResponseEntity<Void> cancelOrder(@PathVariable String id) {
        log.info("REST CancelOrder: id={}", id);
        boolean removed = orderBookManager.removeOrder(UUID.fromString(id));
        if (removed) {
            log.info("REST CancelOrder: Order {} successfully removed", id);
            return ResponseEntity.ok().build();
        } else {
            log.warn("REST CancelOrder: Order {} not found in orderbook", id);
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/account")
    public Account getAccount() {
        Account account = accountManager.getAccount();
        log.debug("REST GetAccount: id={}, balance={}, positions={}",
                account.getId(), account.getBalance(), account.getPositions().size());
        return account;
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
