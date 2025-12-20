# Tinkoff Broker Emulator

Мок-сервер для эмуляции T-Invest API (Тинькофф Брокер). Позволяет разрабатывать и тестировать торговых ботов без подключения к реальной бирже.

## Особенности

- Эмулирует один инструмент — **TBRU** (облигационный ETF)
- **Pro-Rata** алгоритм matching (как на реальной бирже TBRU)
- Поддержка **шорт-позиций** (отрицательный quantity в Position)
- Event-driven архитектура для real-time обновлений
- Web Admin UI для ручного управления стаканом

## Оглавление

- [Технический стек](#технический-стек)
- [Быстрый старт](#быстрый-старт)
- [Конфигурация](#конфигурация)
- [API](#api)
- [Ключевые концепции](#ключевые-концепции)
- [Документация](#документация)
- [Локальная разработка](#локальная-разработка)

## Технический стек

- **Java**: 21
- **Spring Boot**: 3.3.1
- **gRPC**: net.devh:grpc-server-spring-boot-starter
- **Gradle**: система сборки
- **Docker Compose**: для запуска

## Быстрый старт

### Docker (рекомендуется)

```bash
# Сборка и запуск
docker-compose up --build

# Эмулятор доступен:
# - gRPC: localhost:50051
# - Web Admin: http://localhost:8080

# Остановка
docker-compose down
```

### Локальный запуск

```bash
# Сборка + тесты
./gradlew build

# Запуск
./gradlew bootRun

# Эмулятор доступен:
# - gRPC: localhost:9090
# - Web Admin: http://localhost:8080
```

## Конфигурация

Файл `config/application.yml`:

```yaml
emulator:
  # Эмулируемый инструмент
  instrument:
    ticker: "TBRU"
    uid: "e8acd2fb-6de6-4ea4-9bfb-0daad9b2ed7b"  # Используется везде как instrumentId
    figi: "TCS60A1039N1"
    lot: 1
    min-price-increment: 0.01
    currency: "RUB"

  # Начальные настройки стакана
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

  # Настройки счёта бота
  account:
    id: "mock-account-001"
    initial-balance: 200000.00
    margin-multiplier-buy: 7.0    # Множитель для GetMaxLots BUY
    margin-multiplier-sell: 7.1   # Множитель для GetMaxLots SELL
```

## API

### gRPC API

- **Адрес**: `localhost:50051` (Docker) / `localhost:9090` (локально)
- **TLS**: Отключён (plaintext)
- **Авторизация**: `Authorization: Bearer <любой_токен>`

**Реализованные сервисы:**

| Сервис | Методы |
|--------|--------|
| `InstrumentsService` | `FindInstrument` |
| `MarketDataService` | `GetOrderBook` |
| `MarketDataStreamService` | `MarketDataStream` (bidirectional) |
| `OrdersService` | `PostOrder`, `CancelOrder`, `GetOrders`, `GetMaxLots` |
| `OrdersStreamService` | `OrderStateStream` (server stream) |
| `OperationsService` | `GetPortfolio`, `GetPositions`, `GetWithdrawLimits` |
| `UsersService` | `GetAccounts`, `GetInfo` |

### REST API (Web Admin)

- **Адрес**: `http://localhost:8080`

| Метод | Путь | Описание |
|-------|------|----------|
| `GET` | `/api/orderbook` | Текущий стакан |
| `GET` | `/api/orders` | Список всех заявок |
| `POST` | `/api/orders` | Создать заявку (от имени рынка) |
| `DELETE` | `/api/orders/{id}` | Отменить заявку |
| `GET` | `/api/account` | Информация о счёте бота |

### WebSocket

- **Адрес**: `ws://localhost:8080/ws/orderbook`
- Real-time обновления стакана в формате JSON

## Ключевые концепции

### OrderSource — источник заявки

```java
enum OrderSource {
    API,          // Заявки бота через gRPC → влияют на Account
    ADMIN_PANEL   // Заявки "рынка" через Web UI → НЕ влияют на Account
}
```

**Важно:** Баланс и позиции обновляются ТОЛЬКО для сделок, где одна из сторон — `OrderSource.API`.

### Шорт-позиции

`Position.quantity` может быть отрицательным. Правила обновления средней цены:
- **Увеличение позиции** → пересчёт средневзвешенной
- **Уменьшение позиции** → средняя не меняется
- **Пересечение нуля** → средняя = цена исполнения

### Pro-Rata Matching

Объём распределяется пропорционально размеру заявок с floor-округлением, остатки по FIFO. Подробности: `docs/MATCHING_ENGINE.md`

## Документация

| Файл | Описание |
|------|----------|
| `CLAUDE.md` | Руководство для AI-агентов, архитектура, нюансы |
| `docs/ARCHITECTURE.md` | Диаграммы компонентов, потоки данных, thread-safety |
| `docs/API_SPEC.md` | Полная спецификация gRPC и REST API |
| `docs/MATCHING_ENGINE.md` | Алгоритм Pro-Rata с примерами |
| `docs/ROADMAP.md` | История разработки и изменений |

## Локальная разработка

```bash
# Все тесты
./gradlew test

# Только Pro-Rata тесты
./gradlew test --tests "*Pro*"

# Запуск с hot-reload
./gradlew bootRun
```

### Порты

| Запуск | gRPC | Web Admin |
|--------|------|-----------|
| `./gradlew bootRun` | 9090 | 8080 |
| `docker-compose up` | 50051 | 8080 |

### Тесты

- 21 тест (7 integration, 14 unit)
- E2E тест требует Docker

## Известные ограничения

1. **Нет персистентности** — состояние теряется при перезапуске
2. **Один инструмент** — только TBRU
3. **Нет проверки лимитов** — заявки всегда принимаются
4. **Упрощённый P&L** — комиссии не учитываются

---

_Версия: 1.0.0 | Обновлено: 2025-12-20_
