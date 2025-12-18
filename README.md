# Tinkoff Broker Emulator

Мок-сервер для эмуляции T-Invest API (Тинькофф Брокер). Сервер предназначен для тестирования торговых ботов без подключения к реальной бирже.

## Оглавление
- [Технический стек](#технический-стек)
- [Быстрый старт с Docker](#быстрый-старт-с-docker)
- [Конфигурация](#конфигурация)
- [API](#api)
  - [gRPC API](#grpc-api)
  - [REST API (Web Admin)](#rest-api-web-admin)
- [Локальная разработка](#локальная-разработка)

## Технический стек

- **Java**: 21
- **Spring Boot**: 3.3.1
- **gRPC**: для реализации API
- **Gradle**: система сборки
- **Docker Compose**: для запуска

## Быстрый старт с Docker

Это рекомендуемый способ запуска эмулятора.

1.  **Соберите и запустите контейнер:**

    ```bash
    docker-compose up --build
    ```

2.  **Эмулятор будет доступен по адресам:**
    -   **gRPC**: `localhost:50051`
    -   **Web Admin UI**: `http://localhost:8080`

3.  **Остановка эмулятора:**

    ```bash
    docker-compose down
    ```

## Конфигурация

Конфигурация эмулятора находится в файле `config/application.yml`. Вы можете изменять этот файл для настройки параметров инструмента, стакана и счёта.

Пример `config/application.yml`:
```yaml
spring:
  main:
    banner-mode: "off"
  application:
    name: tinkoff-broker-emulator

# gRPC Server
grpc:
  server:
    port: 50051

# Web Server
server:
  port: 8080

emulator:
  # Настройки эмулируемого инструмента
  instrument:
    ticker: "TBRU"
    uid: "e8acd2fb-6de6-4ea4-9bfb-0daad9b2ed7b"
    figi: "TCS60A1039N1"
    lot: 1
    min-price-increment: 0.01
    currency: "RUB"
  
  # Начальные настройки стакана
  orderbook:
    initial-bid: 7.69
    initial-ask: 7.70
    depth: 10
  
  # Настройки счёта бота
  account:
    id: "mock-account-001"
    initial-balance: 200000.00
    margin-multiplier-buy: 7.0
    margin-multiplier-sell: 7.1
```

## API

### gRPC API

- **Адрес**: `localhost:50051` (без TLS)
- **Авторизация**: `Authorization: Bearer <любой_токен>`
- **Proto-контракты**: См. официальную [документацию T-Invest API](https://invest-public-api.tinkoff.ru/docs/).

Реализованы следующие сервисы:
- `InstrumentsService` (`FindInstrument`)
- `MarketDataService` (`GetOrderBook`, `GetTradingStatus`)
- `MarketDataStreamService` (`MarketDataStream`)
- `OrdersService` (`PostOrder`, `CancelOrder`, `GetOrders`, `GetMaxLots`)
- `OrdersStreamService` (`OrderStateStream`)
- `OperationsService` (`GetPortfolio`, `GetPositions`, `GetWithdrawLimits`)
- `UsersService` (`GetAccounts`, `GetInfo`)

### REST API (Web Admin)

- **Адрес**: `http://localhost:8080`

| Метод  | Путь                | Описание                                  |
|--------|---------------------|-------------------------------------------|
| `GET`  | `/api/orderbook`    | Получить текущее состояние стакана.       |
| `GET`  | `/api/orders`       | Получить список всех активных заявок.     |
| `POST` | `/api/orders`       | Создать заявку (от имени участника рынка).|
| `DELETE`| `/api/orders/{id}` | Отменить заявку.                          |
| `GET`  | `/api/account`      | Получить информацию о счёте бота.          |

### WebSocket

- **Адрес**: `ws://localhost:8080/ws/orderbook`
- **Сообщение**: Рассылает обновления стакана в формате JSON.

## Локальная разработка

1.  **Запустите тесты:**

    ```bash
    ./gradlew test
    ```

2.  **Запустите приложение:**

    ```bash
    ./gradlew bootRun
    ```

### Порты

| Запуск | gRPC порт | Web Admin порт | Описание |
|--------|-----------|----------------|----------|
| `./gradlew bootRun` | **9090** | 8080 | Локальная разработка (конфиг из `config/application.yml`) |
| `docker-compose up` | 50051 | 8080 | Docker контейнер |

**Примечание**: При локальном запуске (`./gradlew bootRun`) используется порт **9090** для gRPC (стандарт grpc-spring-boot-starter). Для Docker используется порт 50051 согласно docker-compose.yml.