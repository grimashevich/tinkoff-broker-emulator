package ru.tinkoff.invest.emulator.grpc.service;

import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import ru.tinkoff.invest.emulator.config.EmulatorProperties;
import ru.tinkoff.piapi.contract.v1.*;
import ru.tinkoff.piapi.contract.v1.InstrumentsServiceGrpc.InstrumentsServiceImplBase;

@Slf4j
@GrpcService
@RequiredArgsConstructor
public class InstrumentsServiceImpl extends InstrumentsServiceImplBase {

    private final EmulatorProperties properties;

    @Override
    public void findInstrument(FindInstrumentRequest request, StreamObserver<FindInstrumentResponse> responseObserver) {
        String query = request.getQuery();
        EmulatorProperties.Instrument inst = properties.getInstrument();
        
        // Simple match
        boolean match = query.equalsIgnoreCase(inst.getTicker()) 
                     || query.equalsIgnoreCase(inst.getFigi()) 
                     || query.equalsIgnoreCase(inst.getUid());

        FindInstrumentResponse.Builder builder = FindInstrumentResponse.newBuilder();
        if (match) {
            builder.addInstruments(InstrumentShort.newBuilder()
                    .setTicker(inst.getTicker())
                    .setFigi(inst.getFigi())
                    .setUid(inst.getUid())
                    .setName("Tinkoff Broker Emulator Instrument")
                    .setInstrumentType("bond")
                    .setClassCode("TQBR")
                    .setApiTradeAvailableFlag(true)
                    .build());
        }
        
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }
}
