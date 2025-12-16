# Tinkoff Broker Emulator

## Описание проекта

Мок-сервер для эмуляции T-Invest API (Тинькофф Брокер). Сервер предназначен для тестирования торговых ботов без подключения к реальной бирже. Клиентское приложение (торговый бот) не должно видеть разницы между подключением к реальному серверу T-Broker и к этому эмулятору.

## Технический стек

- **Java**: 21
- **Spring Boot**: 4.0.0
- **gRPC**: для реализации API (как у T-Invest API)
- **Gradle**: система сборки
- **Docker Compose**: для упаковки

## Ключевые требования

### 1. Полная совместимость с T-Invest API

Сервер должен реализовать gRPC сервисы, идентичные T-Invest API:
- Использовать те же proto-контракты
- Принимать **любой токен авторизации** как успешный
- Возвращать данные в том же формате (Quotation, MoneyValue, Timestamp)

### 2. Формат данных

**Quotation** (цены, количества без валюты):
```java
// BigDecimal -> Quotation
BigDecimal value = new BigDecimal("7.69");
Quotation quotation = Quotation.newBuilder()
    .setUnits(value.longValue()) // 7
    .setNano(value.remainder(BigDecimal.ONE)
        .multiply(BigDecimal.valueOf(1_000_000_000)).intValue()) // 690000000
    .build();

// Quotation -> BigDecimal
BigDecimal result = BigDecimal.valueOf(quotation.getUnits())
    .add(BigDecimal.valueOf(quotation.getNano(), 9)); // 7.69
```

**MoneyValue** (цены с валютой):
```java
MoneyValue money = MoneyValue.newBuilder()
    .setCurrency("RUB")
    .setUnits(200000)
    .setNano(0)
    .build();
```

### 3. Эмулируемый инструмент (конфигурируется в application.yml)

```yaml
emulator:
  instrument:
    ticker: "TBRU"
    uid: "e8acd2fb-6de6-4ea4-9bfb-0daad9b2ed7b"
    figi: "TCS60A1039N1"
    lot: 1
    min-price-increment: 0.01  # шаг цены
    currency: "RUB"
  
  orderbook:
    initial-bid: 7.69
    initial-ask: 7.70
    depth: 10  # допустимые значения: 10, 20, 30, 40, 50
  
  account:
    id: "mock-account-001"
    initial-balance: 200000.00  # рубли
    margin-multiplier-buy: 7.0   # для getMaxLots buy
    margin-multiplier-sell: 7.1  # для getMaxLots sell
```

### 4. Matching Engine (Pro-Rata)

Алгоритм пропорционального исполнения заявок:

```
Объем_исполнения = floor(Объем_Агрессора × (Объем_Заявки / Общий_Объем_На_Уровне))
```

**Правила:**
1. Приоритет определяется размером заявки (не временем)
2. Округление всегда вниз (floor)
3. Нераспределённые остатки (хвосты) распределяются по FIFO
4. Частичное исполнение допускается

**Пример:**
- На уровне 7.70: Заявка A = 100 лотов, Заявка B = 50 лотов
- Приходит маркет-ордер на покупку 30 лотов
- A получит: floor(30 × 100/150) = floor(20) = 20 лотов
- B получит: floor(30 × 50/150) = floor(10) = 10 лотов
- Если бы была заявка C = 5 лотов (~3%), она бы получила floor(30 × 5/150) = 1 лот

### 5. Реализуемые gRPC сервисы

#### MarketDataService (Unary)
- `GetOrderBook(instrument_id, depth)` — получение стакана

#### MarketDataStreamService (Bidirectional Stream)  
- `MarketDataStream` — стрим обновлений стакана (OrderBook)

#### OrdersService (Unary)
- `PostOrder` — выставление заявки (лимитная/рыночная)
- `CancelOrder` — отмена заявки
- `GetOrders` — список активных заявок
- `GetMaxLots` — максимум лотов с учётом маржи

#### OrdersStreamService (Server Stream)
- `OrderStateStream` — стрим статусов заявок (исполнение, отмена)

#### OperationsService (Unary)
- `GetPortfolio` — стоимость портфеля
- `GetPositions` — позиции и доступные средства

#### InstrumentsService (Unary)
- `FindInstrument` — поиск инструмента по тикеру

### 6. Web-админка

SPA-приложение (без перезагрузки страницы) для ручного управления:

**Функционал:**
- Визуализация стакана (как в торговом терминале)
- Форма выставления заявок (лимитные и рыночные)
- Формы НЕ очищаются после отправки (для "насыпания" заявок)
- Real-time обновления через WebSocket

**Заявки из админки = другие участники рынка** (не бот)

### 7. Расчёт GetMaxLots

```java
// Для BUY:
maxLotsBuy = floor(portfolioValue × marginMultiplierBuy / currentAskPrice)

// Для SELL:
maxLotsSell = floor(portfolioValue × marginMultiplierSell / currentBidPrice)
// + текущая позиция по инструменту
```

## Архитектурные решения

### Слои приложения

```
┌─────────────────────────────────────────────────────────────┐
│                    Web Admin (SPA)                          │
│                 REST + WebSocket                            │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                    gRPC Services Layer                       │
│  MarketData │ Orders │ Operations │ Instruments             │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                    Core Engine                               │
│  OrderBook │ MatchingEngine │ AccountManager                │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                    Domain Models                             │
│  Order │ Position │ Trade │ Instrument                      │
└─────────────────────────────────────────────────────────────┘
```

### Потокобезопасность

- OrderBook должен быть thread-safe (ConcurrentHashMap, synchronized blocks)
- События об изменениях публикуются через Spring ApplicationEventPublisher
- Стримы используют реактивные потоки (Reactor/RxJava или gRPC StreamObserver)

## Правила разработки

### Код-стайл
- Google Java Style Guide
- Lombok для boilerplate
- Все публичные методы документированы (JavaDoc)
- Покрытие тестами > 80%

### Тесты
- Unit тесты: JUnit 5 + Mockito
- Integration тесты: @SpringBootTest
- gRPC тесты: grpc-testing

### Перед началом работы
1. Запустить `./gradlew test` — убедиться, что все тесты проходят
2. Изучить текущий прогресс в `docs/ROADMAP.md`
3. После завершения этапа — обновить чеклист в ROADMAP.md

### Git
- Коммиты: Conventional Commits (feat:, fix:, test:, docs:)
- Ветка разработки: main

## Полезные ссылки

- [T-Invest API Документация](https://developer.tbank.ru/invest/intro/intro/)
- [Proto-контракты](https://github.com/RussianInvestments/investAPI/tree/main/src/docs/contracts)
- [Java SDK](https://github.com/RussianInvestments/invest-api-java-sdk)
- [Нестандартные типы данных](https://developer.tbank.ru/invest/intro/intro/faq_custom_types)

## Контакты

Проект разрабатывается для тестирования торгового бота TBRU.
