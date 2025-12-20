# Архитектура Tinkoff Broker Emulator

## Обзор

Эмулятор построен на Spring Boot 3.3.1 и использует event-driven архитектуру для связи между компонентами. Ключевые подсистемы:

1. **Core Engine** — управление стаканом и matching
2. **State Management** — управление счётом и позициями
3. **gRPC Services** — API для торгового бота
4. **Web Admin** — REST API и UI для ручного управления

---

## Диаграмма компонентов

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              КЛИЕНТЫ                                         │
├─────────────────────────────────┬───────────────────────────────────────────┤
│  Trading Bot (gRPC)             │  Web Admin (REST + WebSocket)             │
│  - PostOrder                    │  - POST /api/orders                       │
│  - CancelOrder                  │  - GET /api/orderbook                     │
│  - GetPortfolio                 │  - WebSocket /ws/orderbook                │
│  - MarketDataStream             │                                           │
│  - OrderStateStream             │                                           │
└─────────────┬───────────────────┴───────────────────┬───────────────────────┘
              │                                       │
              ▼                                       ▼
┌─────────────────────────────────┐   ┌───────────────────────────────────────┐
│      gRPC Services Layer        │   │       Web Controller Layer            │
│  ┌───────────────────────────┐  │   │  ┌─────────────────────────────────┐  │
│  │ OrdersServiceImpl         │  │   │  │ AdminController                 │  │
│  │ MarketDataServiceImpl     │  │   │  │ - createOrder(source=ADMIN)    │  │
│  │ MarketDataStreamServiceImpl│ │   │  │ - getOrderBook()               │  │
│  │ OrdersStreamServiceImpl   │  │   │  └─────────────────────────────────┘  │
│  │ OperationsServiceImpl     │  │   │  ┌─────────────────────────────────┐  │
│  │ InstrumentsServiceImpl    │  │   │  │ OrderBookWebSocketHandler      │  │
│  └───────────────────────────┘  │   │  │ - broadcast orderbook updates  │  │
└─────────────┬───────────────────┘   │  └─────────────────────────────────┘  │
              │                       └───────────────────┬───────────────────┘
              │                                           │
              ▼                                           ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                            CORE ENGINE                                       │
│  ┌─────────────────────────┐    ┌─────────────────────────────────────────┐ │
│  │    OrderBookManager     │◄───│         ProRataMatchingEngine          │ │
│  │  - bids (TreeMap)       │    │  - executeOrder(Order)                 │ │
│  │  - asks (TreeMap)       │    │  - Pro-Rata + FIFO tail distribution   │ │
│  │  - orderIndex (HashMap) │    │  - Generates Trade objects             │ │
│  │  - ReentrantReadWriteLock│   └─────────────────────────────────────────┘ │
│  └─────────────────────────┘                                                 │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │ Spring Events
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           EVENT SYSTEM                                       │
│  ┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐  │
│  │ TradeExecutedEvent  │  │OrderStateChangedEvent│ │OrderBookChangedEvent│  │
│  │ - trade             │  │ - order              │  │ - orderBook         │  │
│  │ - aggressorSource   │  │ - newStatus          │  │                     │  │
│  │ - passiveSource     │  │                      │  │                     │  │
│  └──────────┬──────────┘  └──────────┬──────────┘  └──────────┬──────────┘  │
└─────────────┼────────────────────────┼────────────────────────┼─────────────┘
              │                        │                        │
              ▼                        ▼                        ▼
┌─────────────────────────┐ ┌─────────────────────┐ ┌─────────────────────────┐
│    AccountManager       │ │ OrdersStreamService │ │ MarketDataStreamService │
│  @EventListener         │ │ @EventListener      │ │ @EventListener          │
│  - updateBalance()      │ │ - broadcastToClients│ │ - broadcastOrderBook()  │
│  - updatePosition()     │ │                     │ │                         │
│  - Only for API orders! │ │                     │ │ WebSocketHandler        │
└─────────────────────────┘ └─────────────────────┘ └─────────────────────────┘
```

---

## Потоки данных

### 1. Выставление заявки (PostOrder)

```
Bot → gRPC PostOrder
         │
         ▼
    OrdersServiceImpl.postOrder()
         │
         ├── Создание Order (source = API)
         │
         ▼
    ProRataMatchingEngine.executeOrder()
         │
         ├── Matching с противоположной стороной стакана
         │   └── Для каждой сделки:
         │       ├── Создание Trade
         │       ├── Публикация TradeExecutedEvent
         │       └── Публикация OrderStateChangedEvent
         │
         ├── Если остаток → OrderBookManager.addOrder()
         │   └── Публикация OrderBookChangedEvent
         │
         ▼
    Возврат PostOrderResponse
```

### 2. Обновление состояния счёта

```
TradeExecutedEvent
         │
         ▼
    AccountManager.onTradeExecuted()
         │
         ├── Проверка: aggressorOrderSource == API?
         │   └── Да: updateState(direction, quantity, price)
         │
         ├── Проверка: passiveOrderSource == API?
         │   └── Да: updateState(opposite direction, quantity, price)
         │
         ▼
    Account.balance и Position.quantity обновлены
```

### 3. Стрим обновлений стакана

```
OrderBookChangedEvent
         │
         ├──────────────────────┬──────────────────────┐
         ▼                      ▼                      ▼
    StreamManager        WebSocketHandler      MarketDataStreamService
    - getSubscribers()   - broadcast()         - broadcastToObservers()
         │                      │                      │
         ▼                      ▼                      ▼
    gRPC Clients         Web UI (Browser)      gRPC MarketDataStream
```

---

## Thread Safety

### OrderBookManager

Использует `ReentrantReadWriteLock` для защиты стакана:

```java
// Чтение (getSnapshot, getBestBid, etc.)
readLock.lock();
try {
    // ... read operations
} finally {
    readLock.unlock();
}

// Запись (addOrder, removeOrder, etc.)
writeLock.lock();
try {
    // ... write operations
    // Публикация события ВНУТРИ блокировки
    eventPublisher.publishEvent(new OrderBookChangedEvent(snapshot));
} finally {
    writeLock.unlock();
}
```

**Важно:** События публикуются внутри write-lock. Если listener попытается вызвать метод OrderBookManager, требующий write-lock, произойдёт deadlock.

### ProRataMatchingEngine

Выполняет matching под write-lock OrderBookManager:

```java
public List<Trade> executeOrder(Order order) {
    writeLock.lock();  // Захватываем блокировку
    try {
        // ... matching logic
        // ... публикация TradeExecutedEvent
        // ... публикация OrderStateChangedEvent
    } finally {
        writeLock.unlock();
    }
}
```

### AccountManager

Методы синхронизированы через `synchronized`:

```java
public synchronized void updateState(...) {
    // Обновление balance и position
}
```

---

## Модель данных

### Order

```java
@Data
@Builder
public class Order {
    private UUID id;
    private String instrumentId;      // Должен быть uid!
    private String accountId;
    private OrderDirection direction; // BUY, SELL
    private OrderType type;           // LIMIT, MARKET
    private BigDecimal price;
    private long quantity;
    private long filledQuantity;
    private OrderStatus status;       // NEW, PARTIALLY_FILLED, FILLED, CANCELLED
    private OrderSource source;       // API, ADMIN_PANEL
    private Instant createdAt;
}
```

### Trade

```java
@Data
@Builder
public class Trade {
    private UUID id;
    private String instrumentId;
    private UUID aggressorOrderId;
    private UUID passiveOrderId;
    private String aggressorAccountId;
    private String passiveAccountId;
    private OrderSource aggressorOrderSource;  // Кто инициировал сделку
    private OrderSource passiveOrderSource;    // Чья заявка была в стакане
    private OrderDirection aggressorDirection; // Направление агрессора
    private BigDecimal price;
    private long quantity;
    private Instant timestamp;
}
```

### Position

```java
@Data
@Builder
public class Position {
    private String instrumentId;
    private long quantity;           // Может быть < 0 (шорт)
    private BigDecimal averagePrice;
    private BigDecimal currentPrice;

    public void update(long quantityDelta, BigDecimal executionPrice) {
        // Логика обновления — см. CLAUDE.md
    }
}
```

---

## Инициализация стакана

При старте `OrderBookManager.init()`:

1. Создаёт `levelsCount` уровней цен (bid и ask)
2. Каждый уровень получает случайный объём в заданном диапазоне
3. На уровнях `bid-offset` и `ask-offset` размещаются "market maker walls" с большим объёмом
4. Все начальные заявки имеют `source = ADMIN_PANEL`

```yaml
orderbook:
  initial-bid: 7.69
  initial-ask: 7.70
  levels-count: 20
  best-price-volume-min: 2000000
  best-price-volume-max: 5000000
  other-volume-min: 10000
  other-volume-max: 500000
  market-maker:
    bid-offset: 12   # 12-й уровень от лучшего bid
    ask-offset: 6    # 6-й уровень от лучшего ask
    volume: 7000000
```

---

## gRPC Services

| Service | Методы | Описание |
|---------|--------|----------|
| `InstrumentsService` | `FindInstrument` | Поиск TBRU |
| `MarketDataService` | `GetOrderBook` | Получение стакана |
| `MarketDataStreamService` | `MarketDataStream` | Bidirectional стрим обновлений |
| `OrdersService` | `PostOrder`, `CancelOrder`, `GetOrders`, `GetMaxLots` | Управление заявками |
| `OrdersStreamService` | `OrderStateStream` | Server-side стрим статусов |
| `OperationsService` | `GetPortfolio`, `GetPositions`, `GetWithdrawLimits` | Информация о счёте |
| `UsersService` | `GetAccounts`, `GetInfo` | Информация о пользователе |

### Авторизация

`AuthInterceptor` принимает любой Bearer token:

```
Authorization: Bearer любой_токен
```

---

## REST API (Web Admin)

| Метод | Путь | Описание |
|-------|------|----------|
| `GET` | `/api/orderbook` | Текущий стакан (depth=20) |
| `GET` | `/api/orders` | Все активные заявки |
| `POST` | `/api/orders` | Создать заявку (source=ADMIN_PANEL) |
| `DELETE` | `/api/orders/{id}` | Отменить заявку |
| `GET` | `/api/account` | Информация о счёте бота |

### WebSocket

```
ws://localhost:8080/ws/orderbook
```

Формат сообщений:
```json
{
  "type": "ORDERBOOK_UPDATE",
  "data": {
    "instrumentId": "e8acd2fb-...",
    "bids": [{"price": 7.69, "quantity": 1000000, "ordersCount": 5}, ...],
    "asks": [{"price": 7.70, "quantity": 500000, "ordersCount": 3}, ...],
    "timestamp": "2025-12-20T20:00:00Z"
  }
}
```

---

## Конфигурация портов

| Режим | gRPC порт | Web порт | Конфигурация |
|-------|-----------|----------|--------------|
| `./gradlew bootRun` | 9090 | 8080 | `config/application.yml` |
| `docker-compose up` | 50051 | 8080 | `docker-compose.yml` переопределяет |

Разница в gRPC портах связана с настройками `grpc-spring-boot-starter` (default: 9090) и явным указанием в `docker-compose.yml` (50051).

---

## Ограничения и известные проблемы

1. **Нет персистентности** — состояние теряется при перезапуске
2. **Один инструмент** — эмулятор поддерживает только TBRU
3. **Нет проверки лимитов** — заявки всегда принимаются
4. **Упрощённый P&L** — комиссии не учитываются
5. **E2E тест требует Docker** — `E2eContainerTest` может падать без Docker
