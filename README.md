# Tinkoff Broker Emulator

## Описание проекта

Мок-сервер для эмуляции T-Invest API (Тинькофф Брокер). Сервер предназначен для тестирования торговых ботов без подключения к реальной бирже. Клиентское приложение (торговый бот) не должно видеть разницы между подключением к реальному серверу T-Broker и к этому эмулятору.

## Технический стек

- **Java**: 21
- **Spring Boot**: 3.3.1
- **gRPC**: для реализации API (как у T-Invest API)
- **Gradle**: система сборки
- **Docker Compose**: для упаковки

## Инструкция по запуску

1. Склонируйте репозиторий:
   ```bash
   git clone https://github.com/your-username/tinkoff-broker-emulator.git
   ```
2. Соберите проект:
   ```bash
   ./gradlew build
   ```
3. Запустите приложение:
   ```bash
   java -jar build/libs/emulator-0.0.1-SNAPSHOT.jar
   ```
Или с помощью Docker Compose:
   ```bash
   docker-compose up --build
   ```

gRPC сервер будет доступен на порту `9090`, а web-админка на `8080`.