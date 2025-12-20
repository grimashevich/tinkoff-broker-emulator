# ⚙️ Pro-Rata Matching Engine

## Обзор

В отличие от стандартного FIFO (First In, First Out), биржа TBRU использует **Pro-Rata** алгоритм исполнения заявок. Это означает, что при частичном исполнении уровня цены объём распределяется пропорционально размеру заявок.

---

## Формула

```
Объём_исполнения = floor(Объём_Агрессора × (Объём_Заявки / Общий_Объём_На_Уровне))
```

Где:
- **Объём_Агрессора** — количество лотов в маркет-ордере или агрессивном лимит-ордере
- **Объём_Заявки** — количество лотов конкретной пассивной заявки на уровне
- **Общий_Объём_На_Уровне** — сумма всех заявок на данном ценовом уровне
- **floor** — округление вниз до целого

---

## Правила

1. **Приоритет по размеру**: Крупные заявки получают бо́льшую долю исполнения
2. **Округление вниз**: Всегда используется floor(), чтобы не исполнить больше, чем доступно
3. **Распределение остатков**: Нераспределённые лоты (хвосты) идут по FIFO (Round Robin по 1 лоту)
4. **Частичное исполнение**: Заявка может быть исполнена частично

---

## Примеры

### Пример 1: Базовое распределение

**Состояние стакана (уровень ask = 7.70):**
| Заявка | Объём | Время создания |
|--------|-------|----------------|
| A | 100 | 10:00:00 |
| B | 50 | 10:00:05 |

**Общий объём на уровне:** 150 лотов

**Входящий маркет-ордер на BUY:** 30 лотов

**Расчёт:**
```
A: floor(30 × 100/150) = floor(20.00) = 20 лотов
B: floor(30 × 50/150)  = floor(10.00) = 10 лотов
Итого распределено: 30 лотов ✓
```

**Результат:**
- Заявка A: исполнено 20 лотов, остаток 80
- Заявка B: исполнено 10 лотов, остаток 40

---

### Пример 2: С округлением и хвостами

**Состояние стакана (уровень ask = 7.70):**
| Заявка | Объём | Время создания |
|--------|-------|----------------|
| A | 100 | 10:00:00 |
| B | 50 | 10:00:05 |
| C | 17 | 10:00:10 |

**Общий объём на уровне:** 167 лотов

**Входящий маркет-ордер на BUY:** 50 лотов

**Расчёт Pro-Rata:**
```
A: floor(50 × 100/167) = floor(29.94) = 29 лотов
B: floor(50 × 50/167)  = floor(14.97) = 14 лотов
C: floor(50 × 17/167)  = floor(5.09)  = 5 лотов
Итого Pro-Rata: 48 лотов
```

**Остаток (хвост):** 50 - 48 = 2 лота

**Распределение хвоста по FIFO:**
- A (первая по времени) получает +1 лот → итого 30
- B (вторая) получает +1 лот → итого 15
- Хвост распределён полностью

**Итоговый результат:**
| Заявка | Исполнено | Остаток |
|--------|-----------|---------|
| A | 30 | 70 |
| B | 15 | 35 |
| C | 5 | 12 |

---

### Пример 3: Мелкая заявка не получает ничего

**Состояние стакана (уровень ask = 7.70):**
| Заявка | Объём | Доля |
|--------|-------|------|
| A | 100 | 66.7% |
| B | 50 | 33.3% |
| C | 1 | 0.7% |

**Общий объём:** 151 лот

**Входящий маркет-ордер:** 10 лотов

**Расчёт:**
```
A: floor(10 × 100/151) = floor(6.62) = 6 лотов
B: floor(10 × 50/151)  = floor(3.31) = 3 лота
C: floor(10 × 1/151)   = floor(0.07) = 0 лотов
Итого: 9 лотов
```

**Хвост (1 лот) → по FIFO к заявке A**

**Результат:** C не получила ничего из-за малого размера.

---

### Пример 4: Market Order съедает несколько уровней

**Состояние стакана:**
| Уровень | Заявки | Общий объём |
|---------|--------|-------------|
| 7.70 | A=50, B=30 | 80 |
| 7.71 | C=100 | 100 |
| 7.72 | D=200 | 200 |

**Входящий маркет-ордер на BUY:** 150 лотов

**Этап 1: Уровень 7.70 (80 лотов)**
```
Доступно для исполнения: 80 лотов
A: floor(80 × 50/80) = 50 (полностью)
B: floor(80 × 30/80) = 30 (полностью)
Исполнено на уровне: 80 лотов
Осталось: 150 - 80 = 70 лотов
```

**Этап 2: Уровень 7.71 (100 лотов)**
```
Доступно для исполнения: 70 лотов (меньше чем на уровне)
C: floor(70 × 100/100) = 70 лотов
Осталось: 0 лотов
```

**Уровень 7.72 не затронут.**

**Итоговые сделки:**
| Цена | Объём |
|------|-------|
| 7.70 | 80 |
| 7.71 | 70 |

---

## Псевдокод

```java
public List<Trade> executeMarketOrder(Order aggressorOrder) {
    List<Trade> trades = new ArrayList<>();
    long remainingQuantity = aggressorOrder.getQuantity();
    
    // Получаем противоположную сторону стакана
    NavigableMap<BigDecimal, PriceLevel> oppositeSide = 
        aggressorOrder.isBuy() ? orderBook.getAsks() : orderBook.getBids();
    
    Iterator<Map.Entry<BigDecimal, PriceLevel>> levelIterator = 
        aggressorOrder.isBuy() ? oppositeSide.entrySet().iterator() 
                               : oppositeSide.descendingMap().entrySet().iterator();
    
    while (remainingQuantity > 0 && levelIterator.hasNext()) {
        Map.Entry<BigDecimal, PriceLevel> entry = levelIterator.next();
        BigDecimal price = entry.getKey();
        PriceLevel level = entry.getValue();
        
        // Сколько можем взять с этого уровня
        long availableOnLevel = level.getTotalQuantity();
        long toExecuteOnLevel = Math.min(remainingQuantity, availableOnLevel);
        
        // Pro-Rata распределение на уровне
        List<Trade> levelTrades = executeProRataOnLevel(
            level, toExecuteOnLevel, price, aggressorOrder
        );
        
        trades.addAll(levelTrades);
        remainingQuantity -= toExecuteOnLevel;
        
        // Удаляем пустой уровень
        if (level.isEmpty()) {
            levelIterator.remove();
        }
    }
    
    return trades;
}

private List<Trade> executeProRataOnLevel(
    PriceLevel level, 
    long quantityToExecute, 
    BigDecimal price,
    Order aggressorOrder
) {
    List<Trade> trades = new ArrayList<>();
    long totalOnLevel = level.getTotalQuantity();
    long distributed = 0;
    
    // Отсортированные по времени заявки (для FIFO хвоста)
    List<Order> ordersOnLevel = level.getOrdersSortedByTime();
    Map<Order, Long> allocations = new LinkedHashMap<>();
    
    // Этап 1: Pro-Rata распределение
    for (Order passiveOrder : ordersOnLevel) {
        long proRataShare = (long) Math.floor(
            (double) quantityToExecute * passiveOrder.getRemainingQuantity() / totalOnLevel
        );
        allocations.put(passiveOrder, proRataShare);
        distributed += proRataShare;
    }
    
    // Этап 2: Распределение хвоста по FIFO (Round Robin)
    long tail = quantityToExecute - distributed;
    while (tail > 0) {
        for (Order passiveOrder : ordersOnLevel) {
            if (tail <= 0) break;
            
            long currentAlloc = allocations.get(passiveOrder);
            long maxCanAdd = passiveOrder.getRemainingQuantity() - currentAlloc;
            
            if (maxCanAdd > 0) {
                allocations.put(passiveOrder, currentAlloc + 1);
                tail--;
            }
        }
    }
    
    // Этап 3: Создание сделок
    for (Map.Entry<Order, Long> alloc : allocations.entrySet()) {
        Order passiveOrder = alloc.getKey();
        long quantity = alloc.getValue();
        
        if (quantity > 0) {
            Trade trade = createTrade(aggressorOrder, passiveOrder, price, quantity);
            trades.add(trade);
            
            // Обновление заявок
            passiveOrder.fill(quantity);
            aggressorOrder.fill(quantity);
            
            // Удаление полностью исполненной заявки
            if (passiveOrder.isFullyFilled()) {
                // Remove from index and level
            }
        }
    }
    
    return trades;
}
```

---

## Тестовые сценарии

### Обязательные unit-тесты:

1. **testBasicProRataDistribution** — базовое распределение без остатка
2. **testProRataWithTailFIFO** — распределение с хвостом по FIFO
3. **testSmallOrderGetsNothing** — мелкая заявка не исполняется
4. **testMultipleLevelExecution** — исполнение через несколько уровней
5. **testSingleOrderOnLevel** — одна заявка на уровне (100% исполнение)
6. **testEqualOrdersSameDistribution** — равные заявки получают равные доли
7. **testPartialLevelExecution** — частичное исполнение уровня

---

## Граничные случаи

| Случай | Поведение |
|--------|-----------|
| Пустой стакан | Маркет-ордер отклоняется |
| Объём агрессора = 0 | Отклонение |
| Одна заявка на уровне | Получает всё |
| Все заявки по 1 лоту | Распределение по FIFO |
| Агрессор больше всего стакана | Частичное исполнение |

---

## Обновление позиций после сделки

После исполнения сделки (Trade) обновляется позиция участника. Логика обновления учитывает возможность шорт-позиций.

### Формула обновления средней цены

```java
public void update(long quantityDelta, BigDecimal executionPrice) {
    long newQuantity = this.quantity + quantityDelta;

    // 1. Пересечение нуля (лонг → шорт или шорт → лонг)
    if (crossingZero(quantity, newQuantity)) {
        averagePrice = executionPrice;
    }
    // 2. Закрытие позиции
    else if (newQuantity == 0) {
        averagePrice = BigDecimal.ZERO;
    }
    // 3. Увеличение позиции — пересчёт средневзвешенной
    else if (isIncreasingPosition(quantityDelta, quantity)) {
        averagePrice = weightedAverage(quantity, averagePrice, quantityDelta, executionPrice);
    }
    // 4. Уменьшение позиции — средняя НЕ меняется

    this.quantity = newQuantity;
}
```

### Сценарии обновления

| Сценарий | До | Операция | После | Средняя цена |
|----------|----|---------:|-------|--------------|
| Открытие лонга | 0 | +100 @ 7.69 | 100 | 7.69 |
| Увеличение лонга | 100 @ 7.69 | +50 @ 7.70 | 150 | 7.693 |
| Частичное закрытие | 100 @ 7.69 | -30 @ 7.71 | 70 | **7.69** (не меняется) |
| Полное закрытие | 100 @ 7.69 | -100 @ 7.71 | 0 | 0 |
| Переворот в шорт | 100 @ 7.69 | -150 @ 7.71 | -50 | **7.71** |
| Открытие шорта | 0 | -100 @ 7.71 | -100 | 7.71 |
| Увеличение шорта | -100 @ 7.71 | -50 @ 7.72 | -150 | 7.713 |
| Закрытие шорта | -100 @ 7.71 | +100 @ 7.69 | 0 | 0 |

### Важные нюансы

1. **quantityDelta положительный для покупки, отрицательный для продажи**
2. **Средняя цена пересчитывается только при увеличении абсолютного размера позиции**
3. **При пересечении нуля новая средняя = цена исполнения последней сделки**
4. **Округление**: 9 знаков после запятой (RoundingMode.HALF_UP)

---

## Trade модель

При исполнении заявки создаётся объект Trade:

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

**AccountManager** обновляет счёт только если хотя бы одна сторона сделки имеет `OrderSource.API`.

---

_Последнее обновление: 2025-12-20_