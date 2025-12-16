package ru.tinkoff.invest.emulator.grpc.mapper;

import com.google.protobuf.Timestamp;
import ru.tinkoff.piapi.contract.v1.MoneyValue;
import ru.tinkoff.piapi.contract.v1.Quotation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public class GrpcMapper {

    public static Quotation toQuotation(BigDecimal value) {
        if (value == null) {
            return Quotation.getDefaultInstance();
        }
        return Quotation.newBuilder()
                .setUnits(value.longValue())
                .setNano(value.remainder(BigDecimal.ONE).multiply(BigDecimal.valueOf(1_000_000_000)).intValue())
                .build();
    }

    public static BigDecimal toBigDecimal(Quotation quotation) {
        if (quotation == null) return BigDecimal.ZERO;
        return BigDecimal.valueOf(quotation.getUnits())
                .add(BigDecimal.valueOf(quotation.getNano(), 9));
    }

    public static Timestamp toTimestamp(Instant instant) {
        if (instant == null) return Timestamp.getDefaultInstance();
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
    
    public static Instant toInstant(Timestamp timestamp) {
        if (timestamp == null) return Instant.now();
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    public static MoneyValue toMoneyValue(BigDecimal amount, String currency) {
        if (amount == null) amount = BigDecimal.ZERO;
        return MoneyValue.newBuilder()
                .setCurrency(currency)
                .setUnits(amount.longValue())
                .setNano(amount.remainder(BigDecimal.ONE).multiply(BigDecimal.valueOf(1_000_000_000)).intValue())
                .build();
    }
}
