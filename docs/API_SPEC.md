# API Specification — Tinkoff Broker Emulator

## Обзор

Эмулятор реализует подмножество T-Invest API, необходимое для работы торгового бота TBRU.

---

## Содержание

- [gRPC Services](#grpc-services)
- [REST API (Web Admin)](#rest-api-web-admin)
- [WebSocket](#websocket)
- [Внутренние модели](#внутренние-модели)
- [Типы данных](#типы-данных)

---

## gRPC Services

### Endpoint

```
localhost:9090   (локальный запуск)
localhost:50051  (Docker)
```

**Без TLS** — plaintext gRPC.

### Авторизация

Все запросы должны содержать metadata:
```
Authorization: Bearer <любой_токен>
```

Эмулятор принимает **любой токен** как валидный.

---

## MarketDataService

### GetOrderBook

Получение текущего состояния стакана.

**Request:**
```protobuf
message GetOrderBookRequest {
  string instrument_id = 1;  // uid или figi инструмента
  int32 depth = 2;           // глубина: 10, 20, 30, 40, 50
}
```

**Response:**
```protobuf
message GetOrderBookResponse {
  string figi = 1;
  int32 depth = 2;
  repeated Order bids = 3;      // заявки на покупку (лучшие сверху)
  repeated Order asks = 4;      // заявки на продажу (лучшие сверху)
  Quotation last_price = 5;
  Quotation close_price = 6;
  Quotation limit_up = 7;
  Quotation limit_down = 8;
  google.protobuf.Timestamp last_price_ts = 9;
  google.protobuf.Timestamp close_price_ts = 10;
  google.protobuf.Timestamp orderbook_ts = 11;
  string instrument_uid = 12;
}

message Order {
  Quotation price = 1;
  int64 quantity = 2;
}
```

---

## MarketDataStreamService

### MarketDataStream (Bidirectional)

Подписка на обновления стакана в реальном времени.

**Request (stream):**
```protobuf
message MarketDataRequest {
  oneof payload {
    SubscribeOrderBookRequest subscribe_order_book_request = 1;
  }
}

message SubscribeOrderBookRequest {
  SubscriptionAction subscription_action = 1;  // SUBSCRIBE или UNSUBSCRIBE
  repeated OrderBookInstrument instruments = 2;
}

message OrderBookInstrument {
  string instrument_id = 1;  // uid или figi
  int32 depth = 2;           // 10, 20, 30, 40, 50
}
```

**Response (stream):**
```protobuf
message MarketDataResponse {
  oneof payload {
    SubscribeOrderBookResponse subscribe_order_book_response = 1;
    OrderBook orderbook = 2;
  }
}

message OrderBook {
  string figi = 1;
  int32 depth = 2;
  bool is_consistent = 3;
  repeated Order bids = 4;
  repeated Order asks = 5;
  google.protobuf.Timestamp time = 6;
  Quotation limit_up = 7;
  Quotation limit_down = 8;
  string instrument_uid = 9;
}
```

**Поведение:**
- Обновления приходят при каждом изменении стакана (добавление/удаление/исполнение заявок)
- Периодическая рассылка каждые 5 секунд (heartbeat)

---

## OrdersService

### PostOrder

Выставление торгового поручения.

**Request:**
```protobuf
message PostOrderRequest {
  string instrument_id = 1;     // uid или figi
  int64 quantity = 2;           // количество лотов
  Quotation price = 3;          // цена (для лимитных)
  OrderDirection direction = 4; // BUY или SELL
  string account_id = 5;
  OrderType order_type = 6;     // LIMIT или MARKET
  string order_id = 7;          // клиентский ID (UUID)
}
```

**Response:**
```protobuf
message PostOrderResponse {
  string order_id = 1;
  OrderExecutionReportStatus execution_report_status = 2;
  int64 lots_requested = 3;
  int64 lots_executed = 4;
  MoneyValue initial_order_price = 5;
  MoneyValue executed_order_price = 6;
  MoneyValue total_order_amount = 7;
  MoneyValue initial_commission = 8;
  MoneyValue executed_commission = 9;
  string figi = 11;
  OrderDirection direction = 12;
  MoneyValue initial_security_price = 13;
  OrderType order_type = 14;
  string message = 15;
  string instrument_uid = 17;
}
```

**Статусы:**
| Статус | Описание |
|--------|----------|
| `NEW` | Заявка в стакане, ожидает исполнения |
| `PARTIALLYFILL` | Частично исполнена |
| `FILL` | Полностью исполнена |
| `CANCELLED` | Отменена |
| `REJECTED` | Отклонена |

### CancelOrder

Отмена заявки.

**Request:**
```protobuf
message CancelOrderRequest {
  string account_id = 1;
  string order_id = 2;
}
```

**Response:**
```protobuf
message CancelOrderResponse {
  google.protobuf.Timestamp time = 1;
}
```

### GetOrders

Список активных заявок.

**Request:**
```protobuf
message GetOrdersRequest {
  string account_id = 1;
}
```

**Response:**
```protobuf
message GetOrdersResponse {
  repeated OrderState orders = 1;
}
```

### GetMaxLots

Расчёт максимального количества лотов для покупки/продажи.

**Request:**
```protobuf
message GetMaxLotsRequest {
  string account_id = 1;
  string instrument_id = 2;
  Quotation price = 3;        // опционально
}
```

**Response:**
```protobuf
message GetMaxLotsResponse {
  string currency = 1;
  BuyLimitsView buy_limits = 2;
  BuyLimitsView buy_margin_limits = 3;
  SellLimitsView sell_limits = 4;
  SellLimitsView sell_margin_limits = 5;
}

message BuyLimitsView {
  int64 buy_money_amount = 1;
  int64 buy_max_lots = 2;
  int64 buy_max_market_lots = 3;  // Используется ботом
}

message SellLimitsView {
  int64 sell_max_lots = 1;
}
```

**Логика расчёта:**
```java
// Покупка
buyMaxLots = floor(portfolioValue × marginMultiplierBuy / price)

// Продажа
sellMaxLots = floor(portfolioValue × marginMultiplierSell / price) + currentPosition
```

Множители из конфигурации:
- `margin-multiplier-buy: 7.0`
- `margin-multiplier-sell: 7.1`

---

## OrdersStreamService

### OrderStateStream (Server Stream)

Стрим обновлений статусов заявок.

**Request:**
```protobuf
message OrderStateStreamRequest {
  repeated string accounts = 1;
}
```

**Response (stream):**
```protobuf
message OrderStateStreamResponse {
  oneof payload {
    OrderState order_state = 1;
  }
}
```

**Поведение:**
- События приходят при каждом изменении статуса заявки
- `PARTIALLYFILL` — содержит delta исполнения (сколько исполнено в этой сделке)

---

## OperationsService

### GetPortfolio

Информация о портфеле.

**Request:**
```protobuf
message PortfolioRequest {
  string account_id = 1;
}
```

**Response:**
```protobuf
message PortfolioResponse {
  MoneyValue total_amount_portfolio = 11;
  repeated PortfolioPosition positions = 7;
  string account_id = 8;
}

message PortfolioPosition {
  string figi = 1;
  string instrument_type = 2;      // "bond" для TBRU
  Quotation quantity = 3;          // Может быть отрицательным (шорт)!
  MoneyValue average_position_price = 4;
  MoneyValue current_price = 8;
  string instrument_uid = 14;
}
```

**Важно:** `quantity` может быть отрицательным при шорт-позиции.

### GetPositions

Позиции и доступные средства.

**Request:**
```protobuf
message PositionsRequest {
  string account_id = 1;
}
```

**Response:**
```protobuf
message PositionsResponse {
  repeated MoneyValue money = 1;
  repeated PositionsSecurities securities = 3;
}

message PositionsSecurities {
  string figi = 1;
  int64 balance = 3;           // Может быть отрицательным (шорт)!
  string instrument_uid = 5;
  string instrument_type = 7;  // "bond"
}
```

### GetWithdrawLimits

Лимиты на вывод средств.

**Request:**
```protobuf
message WithdrawLimitsRequest {
  string account_id = 1;
}
```

**Response:**
```protobuf
message WithdrawLimitsResponse {
  repeated MoneyValue money = 1;
}
```

---

## InstrumentsService

### FindInstrument

Поиск инструмента.

**Request:**
```protobuf
message FindInstrumentRequest {
  string query = 1;  // тикер, figi или uid
}
```

**Response:**
```protobuf
message FindInstrumentResponse {
  repeated InstrumentShort instruments = 1;
}

message InstrumentShort {
  string figi = 2;
  string ticker = 3;
  string instrument_type = 5;  // "bond"
  string name = 6;
  string uid = 7;
  bool api_trade_available_flag = 10;  // true
}
```

---

## UsersService

### GetAccounts

Список счетов.

**Response:**
```protobuf
message GetAccountsResponse {
  repeated Account accounts = 1;
}

message Account {
  string id = 1;              // "mock-account-001"
  AccountType type = 2;       // ACCOUNT_TYPE_TINKOFF
  string name = 3;            // "Mock Account"
  AccountStatus status = 4;   // ACCOUNT_STATUS_OPEN
  AccessLevel access_level = 6; // ACCOUNT_ACCESS_LEVEL_FULL_ACCESS
}
```

### GetInfo

Информация о пользователе.

**Response:**
```protobuf
message GetInfoResponse {
  bool prem_status = 1;        // true
  bool qual_status = 2;        // true
  repeated string qualified_for_work_with = 3;  // ["bond"]
  string tariff = 4;           // "premium"
}
```

---

## REST API (Web Admin)

### Endpoints

| Method | Path | Описание |
|--------|------|----------|
| `GET` | `/api/orderbook` | Текущий стакан |
| `GET` | `/api/orders` | Список всех заявок |
| `POST` | `/api/orders` | Создать заявку |
| `DELETE` | `/api/orders/{id}` | Отменить заявку |
| `GET` | `/api/account` | Информация о счёте |

### GET /api/orderbook

**Response:**
```json
{
  "instrumentId": "e8acd2fb-6de6-4ea4-9bfb-0daad9b2ed7b",
  "depth": 20,
  "timestamp": "2025-12-20T20:00:00Z",
  "bids": [
    {"price": 7.69, "quantity": 1000000, "ordersCount": 5},
    {"price": 7.68, "quantity": 500000, "ordersCount": 3}
  ],
  "asks": [
    {"price": 7.70, "quantity": 800000, "ordersCount": 4},
    {"price": 7.71, "quantity": 300000, "ordersCount": 2}
  ]
}
```

### GET /api/orders

**Response:**
```json
[
  {
    "id": "a1b2c3d4-...",
    "instrumentId": "e8acd2fb-...",
    "accountId": "mock-account-001",
    "direction": "BUY",
    "type": "LIMIT",
    "price": 7.69,
    "quantity": 1000,
    "filledQuantity": 500,
    "status": "PARTIALLY_FILLED",
    "source": "API"
  }
]
```

### POST /api/orders

Создание заявки от имени "рынка" (source = ADMIN_PANEL).

**Request:**
```json
{
  "direction": "BUY",
  "orderType": "LIMIT",
  "price": 7.68,
  "quantity": 100000
}
```

**Важно:** `instrumentId` не передаётся — сервер использует uid из конфигурации.

**Response:**
```json
{
  "id": "a1b2c3d4-...",
  "instrumentId": "e8acd2fb-...",
  "accountId": "admin-market-maker",
  "direction": "BUY",
  "type": "LIMIT",
  "price": 7.68,
  "quantity": 100000,
  "filledQuantity": 0,
  "status": "NEW",
  "source": "ADMIN_PANEL"
}
```

### DELETE /api/orders/{id}

**Response:** `200 OK` или `404 Not Found`

### GET /api/account

**Response:**
```json
{
  "id": "mock-account-001",
  "balance": 187456.78,
  "positions": {
    "e8acd2fb-6de6-4ea4-9bfb-0daad9b2ed7b": {
      "instrumentId": "e8acd2fb-...",
      "quantity": 1500,
      "averagePrice": 7.695,
      "currentPrice": 7.69
    }
  }
}
```

**Примечание:** `quantity` может быть отрицательным при шорт-позиции.

---

## WebSocket

### Endpoint

```
ws://localhost:8080/ws/orderbook
```

### Формат сообщений

```json
{
  "type": "ORDERBOOK_UPDATE",
  "data": {
    "instrumentId": "e8acd2fb-...",
    "depth": 20,
    "timestamp": "2025-12-20T20:00:00Z",
    "bids": [
      {"price": 7.69, "quantity": 1000000, "ordersCount": 5}
    ],
    "asks": [
      {"price": 7.70, "quantity": 800000, "ordersCount": 4}
    ]
  }
}
```

---

## Внутренние модели

### OrderSource

Определяет источник заявки:

```java
enum OrderSource {
    API,          // Заявки бота через gRPC
    ADMIN_PANEL   // Заявки через Web UI
}
```

**Важно:** Только заявки с `source = API` влияют на баланс и позиции счёта.

### Trade (внутренняя модель)

```java
class Trade {
    UUID id;
    String instrumentId;
    UUID aggressorOrderId;        // Кто инициировал сделку
    UUID passiveOrderId;          // Чья заявка была в стакане
    String aggressorAccountId;
    String passiveAccountId;
    OrderSource aggressorOrderSource;  // API или ADMIN_PANEL
    OrderSource passiveOrderSource;
    OrderDirection aggressorDirection; // BUY или SELL
    BigDecimal price;
    long quantity;
    Instant timestamp;
}
```

Эта модель используется для определения, нужно ли обновлять счёт бота.

---

## Типы данных

### Quotation

```json
{
  "units": 7,
  "nano": 690000000
}
// = 7.69
```

Формула: `value = units + nano / 1_000_000_000`

### MoneyValue

```json
{
  "currency": "RUB",
  "units": 200000,
  "nano": 0
}
// = 200000.00 RUB
```

### Timestamp

```json
{
  "seconds": 1734724800,
  "nanos": 0
}
// = 2025-12-20T20:00:00Z
```
