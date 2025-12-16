# 📡 API Specification — Tinkoff Broker Emulator

## Обзор

Эмулятор реализует подмножество T-Invest API, необходимое для работы торгового бота TBRU.

---

## gRPC Services

### Endpoint

```
localhost:50051 (без TLS)
```

### Авторизация

Все запросы должны содержать metadata:
```
Authorization: Bearer <any_token>
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
    // другие подписки не реализованы в MVP
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
  google.protobuf.Timestamp orderbook_ts = 10;
}
```

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

enum OrderDirection {
  ORDER_DIRECTION_UNSPECIFIED = 0;
  ORDER_DIRECTION_BUY = 1;
  ORDER_DIRECTION_SELL = 2;
}

enum OrderType {
  ORDER_TYPE_UNSPECIFIED = 0;
  ORDER_TYPE_LIMIT = 1;
  ORDER_TYPE_MARKET = 2;
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
  MoneyValue aci_value = 10;
  string figi = 11;
  OrderDirection direction = 12;
  MoneyValue initial_security_price = 13;
  OrderType order_type = 14;
  string message = 15;
  Quotation initial_order_price_pt = 16;
  string instrument_uid = 17;
  string order_request_id = 18;
  ResponseMetadata response_metadata = 19;
}

enum OrderExecutionReportStatus {
  EXECUTION_REPORT_STATUS_UNSPECIFIED = 0;
  EXECUTION_REPORT_STATUS_FILL = 1;           // исполнена
  EXECUTION_REPORT_STATUS_REJECTED = 2;       // отклонена
  EXECUTION_REPORT_STATUS_CANCELLED = 3;      // отменена
  EXECUTION_REPORT_STATUS_NEW = 4;            // новая
  EXECUTION_REPORT_STATUS_PARTIALLYFILL = 5;  // частично исполнена
}
```

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
  ResponseMetadata response_metadata = 2;
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

message OrderState {
  string order_id = 1;
  OrderExecutionReportStatus execution_report_status = 2;
  int64 lots_requested = 3;
  int64 lots_executed = 4;
  MoneyValue initial_order_price = 5;
  MoneyValue executed_order_price = 6;
  MoneyValue total_order_amount = 7;
  MoneyValue average_position_price = 8;
  MoneyValue initial_commission = 9;
  MoneyValue executed_commission = 10;
  string figi = 11;
  OrderDirection direction = 12;
  MoneyValue initial_security_price = 13;
  repeated OrderStage stages = 14;
  MoneyValue service_commission = 15;
  string currency = 16;
  OrderType order_type = 17;
  google.protobuf.Timestamp order_date = 18;
  string instrument_uid = 19;
  string order_request_id = 20;
}
```

### GetMaxLots

Расчёт максимального количества лотов.

**Request:**
```protobuf
message GetMaxLotsRequest {
  string account_id = 1;
  string instrument_id = 2;
  Quotation price = 3;        // опционально, для расчёта по конкретной цене
}
```

**Response:**
```protobuf
message GetMaxLotsResponse {
  string currency = 1;
  BuyLimitsView buy_limits = 2;
  BuyLimitsView buy_margin_limits = 3;  // с учётом маржи
  SellLimitsView sell_limits = 4;
  SellLimitsView sell_margin_limits = 5;
}

message BuyLimitsView {
  int64 buy_money_amount = 1;
  int64 buy_max_lots = 2;
  int64 buy_max_market_lots = 3;
}

message SellLimitsView {
  int64 sell_max_lots = 1;
}
```

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

---

## OperationsService

### GetPortfolio

Информация о портфеле.

**Request:**
```protobuf
message PortfolioRequest {
  string account_id = 1;
  PortfolioRequest.CurrencyRequest currency = 2;
}
```

**Response:**
```protobuf
message PortfolioResponse {
  MoneyValue total_amount_shares = 1;
  MoneyValue total_amount_bonds = 2;
  MoneyValue total_amount_etf = 3;
  MoneyValue total_amount_currencies = 4;
  MoneyValue total_amount_futures = 5;
  Quotation expected_yield = 6;
  repeated PortfolioPosition positions = 7;
  string account_id = 8;
  MoneyValue total_amount_options = 9;
  MoneyValue total_amount_sp = 10;
  MoneyValue total_amount_portfolio = 11;
  repeated VirtualPortfolioPosition virtual_positions = 12;
}

message PortfolioPosition {
  string figi = 1;
  string instrument_type = 2;
  Quotation quantity = 3;
  MoneyValue average_position_price = 4;
  Quotation expected_yield = 5;
  MoneyValue current_nkd = 6;
  Quotation average_position_price_pt = 7;
  MoneyValue current_price = 8;
  MoneyValue average_position_price_fifo = 9;
  Quotation quantity_lots = 10;
  bool blocked = 11;
  string blocked_lots = 12;
  string position_uid = 13;
  string instrument_uid = 14;
  MoneyValue var_margin = 15;
  Quotation expected_yield_fifo = 16;
}
```

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
  repeated MoneyValue blocked = 2;
  repeated PositionsSecurities securities = 3;
  bool limits_loading_in_progress = 4;
  repeated PositionsFutures futures = 5;
  repeated PositionsOptions options = 6;
}

message PositionsSecurities {
  string figi = 1;
  int64 blocked = 2;
  int64 balance = 3;
  string position_uid = 4;
  string instrument_uid = 5;
  bool exchange_blocked = 6;
  string instrument_type = 7;
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
  string isin = 1;
  string figi = 2;
  string ticker = 3;
  string class_code = 4;
  string instrument_type = 5;
  string name = 6;
  string uid = 7;
  string position_uid = 8;
  InstrumentType instrument_kind = 9;
  string api_trade_available_flag = 10;
  bool for_iis_flag = 11;
  google.protobuf.Timestamp first_1min_candle_date = 12;
  google.protobuf.Timestamp first_1day_candle_date = 13;
  bool for_qual_investor_flag = 14;
  bool weekend_flag = 15;
  bool blocked_tca_flag = 16;
}
```

---

## REST API (Web Admin)

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/orderbook` | Текущий стакан |
| GET | `/api/orders` | Список всех заявок |
| POST | `/api/orders` | Создать заявку (от участников рынка) |
| DELETE | `/api/orders/{id}` | Отменить заявку |
| GET | `/api/account` | Информация об аккаунте бота |

### WebSocket

```
ws://localhost:8080/ws/orderbook
```

Сообщения в формате JSON:
```json
{
  "type": "ORDERBOOK_UPDATE",
  "data": {
    "bids": [{"price": "7.69", "quantity": 100}, ...],
    "asks": [{"price": "7.70", "quantity": 50}, ...],
    "timestamp": "2025-01-15T10:30:00Z"
  }
}
```

### Пример создания заявки (REST)

```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "direction": "BUY",
    "orderType": "LIMIT",
    "price": "7.68",
    "quantity": 100
  }'
```

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
  "seconds": 1705312200,
  "nanos": 0
}
// = 2024-01-15T10:30:00Z
```
