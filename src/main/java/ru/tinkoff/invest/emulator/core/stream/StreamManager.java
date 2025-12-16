package ru.tinkoff.invest.emulator.core.stream;

import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Slf4j
@Service
public class StreamManager {

    private final Map<StreamObserver<?>, Subscription<?>> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, Set<StreamObserver<?>>> orderBookSubscribers = new ConcurrentHashMap<>();
    private final Map<String, Set<StreamObserver<?>>> accountSubscribers = new ConcurrentHashMap<>();

    public <T> void addOrderBookSubscription(StreamObserver<T> observer, String instrumentId) {
        orderBookSubscribers.computeIfAbsent(instrumentId, k -> ConcurrentHashMap.newKeySet()).add(observer);
        subscriptions.put(observer, new Subscription<>(observer, instrumentId));
        log.info("Added OrderBook subscription for {}", instrumentId);
    }

    public <T> void addOrderStateSubscription(StreamObserver<T> observer, String accountId) {
        accountSubscribers.computeIfAbsent(accountId, k -> ConcurrentHashMap.newKeySet()).add(observer);
        subscriptions.put(observer, new Subscription<>(observer, accountId));
        log.info("Added OrderState subscription for account {}", accountId);
    }

    public void removeSubscription(StreamObserver<?> observer) {
        Subscription<?> sub = subscriptions.remove(observer);
        if (sub != null) {
            if (orderBookSubscribers.containsKey(sub.key)) {
                orderBookSubscribers.get(sub.key).remove(observer);
            }
            if (accountSubscribers.containsKey(sub.key)) {
                accountSubscribers.get(sub.key).remove(observer);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <T> void broadcastOrderBook(String instrumentId, T message) {
        Set<StreamObserver<?>> observers = orderBookSubscribers.get(instrumentId);
        if (observers != null) {
            observers.forEach(obs -> {
                try {
                    ((StreamObserver<T>) obs).onNext(message);
                } catch (Exception e) {
                    log.warn("Failed to send to observer, removing: {}", e.getMessage());
                    removeSubscription(obs);
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    public <T> void broadcastOrderState(String accountId, T message) {
        Set<StreamObserver<?>> observers = accountSubscribers.get(accountId);
        if (observers != null) {
            observers.forEach(obs -> {
                try {
                    ((StreamObserver<T>) obs).onNext(message);
                } catch (Exception e) {
                    log.warn("Failed to send to observer, removing: {}", e.getMessage());
                    removeSubscription(obs);
                }
            });
        }
    }

    private record Subscription<T>(StreamObserver<T> observer, String key) {}
}
