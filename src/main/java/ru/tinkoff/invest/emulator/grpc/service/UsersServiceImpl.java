package ru.tinkoff.invest.emulator.grpc.service;

import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import ru.tinkoff.invest.emulator.config.EmulatorProperties;
import ru.tinkoff.piapi.contract.v1.*;
import ru.tinkoff.piapi.contract.v1.UsersServiceGrpc.UsersServiceImplBase;

@Slf4j
@GrpcService
@RequiredArgsConstructor
public class UsersServiceImpl extends UsersServiceImplBase {

    private final EmulatorProperties properties;

    @Override
    public void getAccounts(GetAccountsRequest request, StreamObserver<GetAccountsResponse> responseObserver) {
        log.info("GRPC GetAccounts: status={}", request.getStatus());

        String accountId = properties.getAccount().getId();

        Account account = Account.newBuilder()
                .setId(accountId)
                .setName("Mock Account")
                .setType(AccountType.ACCOUNT_TYPE_TINKOFF)
                .setStatus(AccountStatus.ACCOUNT_STATUS_OPEN)
                .setAccessLevel(AccessLevel.ACCOUNT_ACCESS_LEVEL_FULL_ACCESS)
                .build();

        GetAccountsResponse response = GetAccountsResponse.newBuilder()
                .addAccounts(account)
                .build();

        log.info("GRPC GetAccounts: Returning account id={}", accountId);

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void getInfo(GetInfoRequest request, StreamObserver<GetInfoResponse> responseObserver) {
        log.info("GRPC GetInfo");

        GetInfoResponse response = GetInfoResponse.newBuilder()
                .setPremStatus(false)
                .setQualStatus(true)
                .setTariff("investor")
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
