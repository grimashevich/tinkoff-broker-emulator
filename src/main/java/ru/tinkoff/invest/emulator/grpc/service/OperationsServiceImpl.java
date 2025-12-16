package ru.tinkoff.invest.emulator.grpc.service;

import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import ru.tinkoff.invest.emulator.core.model.Account;
import ru.tinkoff.invest.emulator.core.model.Position;
import ru.tinkoff.invest.emulator.core.orderbook.OrderBookManager;
import ru.tinkoff.invest.emulator.core.state.AccountManager;
import ru.tinkoff.invest.emulator.grpc.mapper.GrpcMapper;
import ru.tinkoff.piapi.contract.v1.*;
import ru.tinkoff.piapi.contract.v1.OperationsServiceGrpc.OperationsServiceImplBase;

import java.math.BigDecimal;
import java.util.stream.Collectors;

@Slf4j
@GrpcService
@RequiredArgsConstructor
public class OperationsServiceImpl extends OperationsServiceImplBase {

    private final AccountManager accountManager;
    private final OrderBookManager orderBookManager; // To get current prices for valuation

    @Override
    public void getPortfolio(PortfolioRequest request, StreamObserver<PortfolioResponse> responseObserver) {
        Account account = accountManager.getAccount();
        // Calculate totals
        // We need current price for each position.
        // Assuming single instrument TBRU.
        BigDecimal price = orderBookManager.getBestBid(); // Valuation at Bid? Or last price?
        if (price == null) price = orderBookManager.getBestAsk();
        if (price == null) price = BigDecimal.TEN; // Fallback
        
        final BigDecimal finalPrice = price;

        PortfolioResponse response = PortfolioResponse.newBuilder()
                .setTotalAmountPortfolio(GrpcMapper.toMoneyValue(accountManager.getPortfolioValue(finalPrice), "RUB"))
                .setTotalAmountCurrencies(GrpcMapper.toMoneyValue(account.getBalance(), "RUB"))
                .setAccountId(account.getId())
                .addAllPositions(account.getPositions().values().stream()
                        .map(p -> mapPosition(p, finalPrice))
                        .collect(Collectors.toList()))
                .build();
        
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void getPositions(PositionsRequest request, StreamObserver<PositionsResponse> responseObserver) {
        Account account = accountManager.getAccount();
        
        PositionsResponse response = PositionsResponse.newBuilder()
                .addMoney(GrpcMapper.toMoneyValue(account.getBalance(), "RUB"))
                .addAllSecurities(account.getPositions().values().stream()
                        .map(this::mapSecurity)
                        .collect(Collectors.toList()))
                .build();
        
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private PortfolioPosition mapPosition(Position p, BigDecimal currentPrice) {
        return PortfolioPosition.newBuilder()
                .setFigi(p.getInstrumentId())
                .setInstrumentType("share")
                .setQuantity(GrpcMapper.toQuotation(BigDecimal.valueOf(p.getQuantity())))
                .setAveragePositionPrice(GrpcMapper.toMoneyValue(p.getAveragePrice(), "RUB"))
                .setCurrentPrice(GrpcMapper.toMoneyValue(currentPrice, "RUB"))
                .build();
    }

    private PositionsSecurities mapSecurity(Position p) {
        return PositionsSecurities.newBuilder()
                .setFigi(p.getInstrumentId())
                .setBalance(p.getQuantity())
                .setInstrumentType("share")
                .build();
    }
}
