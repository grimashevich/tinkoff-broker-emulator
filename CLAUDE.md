# CLAUDE.md — Tinkoff Broker Emulator

## Что это
Мок-сервер T-Invest API для тестирования торговых ботов без подключения к реальной бирже. Эмулирует один инструмент — TBRU (облигационный ETF).

## Зачем
Позволяет разрабатывать и тестировать торгового бота TbruTrader в изолированной среде с контролируемым стаканом и предсказуемым поведением.

## Стек
- Java 21, Spring Boot 3.3.1, Gradle
- gRPC (net.devh:grpc-server-spring-boot-starter)
- Lombok, JUnit 5

## Структура проекта
```
src/main/java/ru/tinkoff/invest/emulator/
├── config/              # Конфигурация
│   ├── EmulatorProperties.java   # POJO из application.yml
│   ├── GrpcConfig.java           # gRPC сервер
│   ├── WebSocketConfig.java      # WebSocket
│   └── LogCleanupConfig.java     # Ротация логов
├── core/
│   ├── model/           # Доменные модели
│   │   ├── Order.java            # Заявка (id, direction, price, quantity, status, source)
│   │   ├── Trade.java            # Сделка (aggressor/passive стороны, source, direction)
│   │   ├── Account.java          # Счёт (balance, positions)
│   │   ├── Position.java         # Позиция (quantity, averagePrice) — поддерживает шорт!
│   │   ├── OrderBook.java        # Стакан (TreeMap bids/asks)
│   │   ├── PriceLevel.java       # Уровень цены (список заявок)
│   │   └── OrderSource.java      # API или ADMIN_PANEL
│   ├── orderbook/       # OrderBookManager (thread-safe, ReentrantReadWriteLock)
│   ├── matching/        # ProRataMatchingEngine
│   ├── state/           # AccountManager (слушает TradeExecutedEvent)
│   ├── event/           # Spring Events
│   │   ├── TradeExecutedEvent.java
│   │   ├── OrderStateChangedEvent.java
│   │   └── OrderBookChangedEvent.java
│   └── stream/          # StreamManager (подписки на стримы)
├── grpc/
│   ├── service/         # gRPC реализации
│   ├── interceptor/     # AuthInterceptor (принимает любой Bearer token)
│   └── mapper/          # GrpcMapper (BigDecimal ↔ Quotation)
└── web/
    ├── controller/      # AdminController (REST API)
    ├── handler/         # OrderBookWebSocketHandler
    └── dto/             # DTO для REST
```

## Как работать

### Сборка и тесты
```bash
./gradlew build      # Сборка + тесты
./gradlew test       # Только тесты
./gradlew bootRun    # Запуск локально (gRPC: 9090, Web: 8080)
```

### Docker
```bash
docker-compose up --build   # Запуск (gRPC: 50051, Web: 8080)
docker-compose down         # Остановка
```

## Ключевые концепции

### OrderSource — источник заявки
```java
enum OrderSource {
    API,          // Заявки бота через gRPC → влияют на Account
    ADMIN_PANEL   // Заявки "рынка" через Web UI → НЕ влияют на Account
}
```
**Важно:** `AccountManager.onTradeExecuted()` обновляет баланс и позицию ТОЛЬКО для сделок, где одна из сторон — `OrderSource.API`.

### Position — поддержка шорт-позиций
Позиция может быть отрицательной (шорт). Правила обновления `averagePrice`:
- **При увеличении позиции** (покупка для лонга, продажа для шорта) — пересчитывается средневзвешенная
- **При уменьшении позиции** — средняя НЕ меняется
- **При пересечении нуля** (лонг→шорт или шорт→лонг) — средняя = цена исполнения

### Pro-Rata Matching
Алгоритм распределяет объём пропорционально размеру заявок с floor-округлением, остатки по FIFO. Детали: `docs/MATCHING_ENGINE.md`

### Event-Driven Architecture
```
PostOrder → MatchingEngine → TradeExecutedEvent → AccountManager.onTradeExecuted()
                          → OrderStateChangedEvent → OrdersStreamService (gRPC стрим)
         → OrderBookChangedEvent → MarketDataStreamService (gRPC стрим)
                                → WebSocketHandler (Web UI)
```

### GetMaxLots — расчёт лимитов
```java
// BUY: floor(portfolioValue × marginMultiplierBuy / price)
// SELL: floor(portfolioValue × marginMultiplierSell / price) + currentPosition
```
Множители из конфига: `margin-multiplier-buy: 7.0`, `margin-multiplier-sell: 7.1`

## Конфигурация

Файл `config/application.yml`:

```yaml
emulator:
  instrument:
    ticker: "TBRU"
    uid: "e8acd2fb-6de6-4ea4-9bfb-0daad9b2ed7b"  # Используется как instrumentId везде!
    figi: "TCS60A1039N1"
    lot: 1
    min-price-increment: 0.01
    currency: "RUB"

  orderbook:
    initial-bid: 7.69
    initial-ask: 7.70
    depth: 10
    levels-count: 20              # Кол-во уровней при инициализации
    best-price-volume-min: 2000000
    best-price-volume-max: 5000000
    other-volume-min: 10000
    other-volume-max: 500000
    market-maker:
      bid-offset: 12              # Уровень для большой bid-стены
      ask-offset: 6               # Уровень для большой ask-стены
      volume: 7000000             # Объём стены

  account:
    id: "mock-account-001"
    initial-balance: 200000.00
    margin-multiplier-buy: 7.0
    margin-multiplier-sell: 7.1
```

## Частые ошибки и нюансы

### 1. instrumentId должен быть uid, не ticker
Бот использует `uid` для идентификации инструмента. Web UI и REST API должны использовать тот же `uid`, иначе будут создаваться разные позиции.

### 2. Позиция может уйти в минус
При шорт-продаже `position.quantity` становится отрицательным — это нормальное поведение.

### 3. Trade содержит обе стороны сделки
```java
class Trade {
    OrderSource aggressorOrderSource;  // Кто инициировал
    OrderSource passiveOrderSource;    // Кто был в стакане
    OrderDirection aggressorDirection; // BUY или SELL
}
```
`AccountManager` проверяет ОБЕ стороны — если хотя бы одна `API`, обновляет счёт.

### 4. Блокировки в OrderBookManager
Используется `ReentrantReadWriteLock`. При добавлении listener'ов к событиям не делай синхронных вызовов обратно в OrderBookManager — будет deadlock.

### 5. Web UI — статические файлы
`src/main/resources/static/index.html` — при изменении нужен перезапуск или hot-reload.

## Документация
| Файл | Описание |
|------|----------|
| `README.md` | Quick start, конфигурация, API |
| `docs/ARCHITECTURE.md` | Архитектура, потоки данных, thread-safety |
| `docs/API_SPEC.md` | gRPC endpoints, REST API, WebSocket |
| `docs/MATCHING_ENGINE.md` | Алгоритм Pro-Rata с примерами |
| `docs/ROADMAP.md` | Этапы разработки, история изменений |

## Тесты
```bash
./gradlew test                    # Все тесты
./gradlew test --tests "*Pro*"    # Только Pro-Rata тесты
```

Покрытие: 21 тест (7 integration, 14 unit). E2E тест требует Docker.
