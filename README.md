# 🏦 Tinkoff Broker Emulator

Мок-сервер для эмуляции T-Invest API (Тинькофф Брокер). Предназначен для тестирования торговых ботов без подключения к реальной бирже.

## ✨ Возможности

- **Полная совместимость** с T-Invest API (gRPC)
- **Pro-Rata Matching Engine** — пропорциональное исполнение заявок как на реальной бирже
- **Web-админка** для ручного управления стаканом
- **Real-time обновления** через gRPC streams и WebSocket
- **Гибкая конфигурация** инструмента, баланса и маржи

## 🛠️ Технологии

- Java 21
- Spring Boot 4.0.0
- gRPC
- Docker Compose

## 🚀 Быстрый старт

### Docker (рекомендуется)

```bash
docker-compose up -d
```

### Локально

```bash
./gradlew bootRun
```

## 📡 Endpoints

| Сервис | Порт | Протокол |
|--------|------|----------|
| gRPC API | 50051 | gRPC |
| Web Admin | 8080 | HTTP |
| WebSocket | 8080 | WS |

## ⚙️ Конфигурация

Файл `application.yml`:

```yaml
emulator:
  instrument:
    ticker: "TBRU"
    uid: "e8acd2fb-6de6-4ea4-9bfb-0daad9b2ed7b"
    figi: "TCS60A1039N1"
    lot: 1
    min-price-increment: 0.01
    currency: "RUB"
  
  orderbook:
    initial-bid: 7.69
    initial-ask: 7.70
    depth: 10
  
  account:
    id: "mock-account-001"
    initial-balance: 200000.00
    margin-multiplier-buy: 7.0
    margin-multiplier-sell: 7.1
```

## 🔌 Подключение торгового бота

Измените endpoint в конфигурации вашего бота:

```yaml
# Было (реальный сервер)
endpoint: invest-public-api.tinkoff.ru:443

# Стало (эмулятор)
endpoint: localhost:50051
tls: false
```

Эмулятор принимает **любой токен** авторизации.

## 📖 Документация

- [API Specification](docs/API_SPEC.md)
- [Matching Engine Algorithm](docs/MATCHING_ENGINE.md)
- [Development Roadmap](docs/ROADMAP.md)

## 🧪 Тесты

```bash
./gradlew test
```

## 📄 Лицензия

MIT
