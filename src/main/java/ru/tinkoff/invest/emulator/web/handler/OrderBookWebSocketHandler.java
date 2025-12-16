package ru.tinkoff.invest.emulator.web.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import ru.tinkoff.invest.emulator.core.event.OrderBookChangedEvent;
import ru.tinkoff.invest.emulator.web.dto.OrderBookDto;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderBookWebSocketHandler extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    @EventListener
    public void handleOrderBookChange(OrderBookChangedEvent event) {
        if (sessions.isEmpty()) return;

        try {
            OrderBookDto dto = OrderBookDto.builder()
                    .instrumentId(event.getOrderBook().getInstrumentId())
                    .depth(50)
                    .timestamp(Instant.now())
                    .bids(event.getOrderBook().getBids().entrySet().stream()
                            .map(e -> OrderBookDto.PriceLevelDto.builder()
                                    .price(e.getKey())
                                    .quantity(e.getValue().getTotalQuantity())
                                    .ordersCount(e.getValue().getOrders().size())
                                    .build())
                            .collect(Collectors.toList()))
                    .asks(event.getOrderBook().getAsks().entrySet().stream()
                            .map(e -> OrderBookDto.PriceLevelDto.builder()
                                    .price(e.getKey())
                                    .quantity(e.getValue().getTotalQuantity())
                                    .ordersCount(e.getValue().getOrders().size())
                                    .build())
                            .collect(Collectors.toList()))
                    .build();

            // Wrap in "type/data" envelope
            String message = objectMapper.writeValueAsString(new WebSocketMessage("ORDERBOOK_UPDATE", dto));
            
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(message));
                }
            }
        } catch (IOException e) {
            log.error("Failed to broadcast orderbook", e);
        }
    }

    private record WebSocketMessage(String type, Object data) {}
}
