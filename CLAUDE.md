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
├── config/          # EmulatorProperties, GrpcConfig
├── core/
│   ├── model/       # Order, Trade, Account, Position, OrderBook
│   ├── orderbook/   # OrderBookManager (thread-safe)
│   ├── matching/    # ProRataMatchingEngine
│   ├── state/       # AccountManager
│   └── event/       # Spring Events (TradeExecuted, OrderStateChanged)
├── grpc/service/    # gRPC implementations
└── web/             # REST API + WebSocket для Admin UI
```

## Как работать

### Сборка и тесты
```bash
./gradlew build      # Сборка + тесты
./gradlew test       # Только тесты
./gradlew bootRun    # Запуск локально
```

### Docker
```bash
docker-compose up --build   # Запуск
docker-compose down         # Остановка
```

### Порты
- `50051` — gRPC API
- `8080` — Web Admin UI + REST API

## Ключевые особенности

**Pro-Rata Matching**: Алгоритм распределяет объём пропорционально размеру заявок с floor-округлением, остатки по FIFO. Детали: `docs/MATCHING_ENGINE.md`

**Два источника заявок**:
- `OrderSource.API` — заявки бота через gRPC
- `OrderSource.ADMIN_PANEL` — заявки "рынка" через Web UI

**События**: TradeExecutedEvent автоматически обновляет AccountManager при исполнении сделок.

## Документация
| Файл | Описание |
|------|----------|
| `docs/ROADMAP.md` | Этапы разработки, статусы |
| `docs/API_SPEC.md` | gRPC endpoints и форматы |
| `docs/MATCHING_ENGINE.md` | Алгоритм Pro-Rata |
| `.gemini/GEMINI.md` | Полная спецификация проекта |
| `README.md` | Quick start, конфигурация |

## Конфигурация
Файл `config/application.yml` — настройки инструмента, стакана, счёта. Важные параметры:
- `emulator.instrument.*` — TBRU (ticker, uid, figi)
- `emulator.orderbook.initial-bid/ask` — начальные цены
- `emulator.account.margin-multiplier-*` — множители для GetMaxLots
