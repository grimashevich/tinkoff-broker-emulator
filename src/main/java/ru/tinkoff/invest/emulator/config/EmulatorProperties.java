package ru.tinkoff.invest.emulator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

@Data
@Configuration
@ConfigurationProperties(prefix = "emulator")
public class EmulatorProperties {
    private Instrument instrument;
    private OrderBook orderbook;
    private Account account;

    @Data
    public static class Instrument {
        private String ticker;
        private String uid;
        private String figi;
        private int lot;
        private BigDecimal minPriceIncrement;
        private String currency;
    }

    @Data
    public static class OrderBook {
        private BigDecimal initialBid;
        private BigDecimal initialAsk;
        private int depth;
        private int levelsCount = 20;
        private long bestPriceVolumeMin = 2000000;
        private long bestPriceVolumeMax = 5000000;
        private long otherVolumeMin = 10000;
        private long otherVolumeMax = 500000;
        private MarketMaker marketMaker = new MarketMaker();
    }

    @Data
    public static class MarketMaker {
        private int bidOffset = 12;
        private int askOffset = 6;
        private long volume = 7000000;
    }

    @Data
    public static class Account {
        private String id;
        private BigDecimal initialBalance;
        private BigDecimal marginMultiplierBuy;
        private BigDecimal marginMultiplierSell;
    }
}
