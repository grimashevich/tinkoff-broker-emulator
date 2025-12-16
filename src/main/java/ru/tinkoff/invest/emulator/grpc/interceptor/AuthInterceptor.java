package ru.tinkoff.invest.emulator.grpc.interceptor;

import io.grpc.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AuthInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> AUTHORIZATION_KEY = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        String token = headers.get(AUTHORIZATION_KEY);
        if (token != null && token.startsWith("Bearer ")) {
            log.trace("Authenticated request with token: {}", token.substring(0, Math.min(15, token.length())) + "...");
        } else {
            log.debug("Request without Authorization header or invalid format");
        }

        return next.startCall(new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void sendHeaders(Metadata headers) {
                super.sendHeaders(headers);
            }
        }, headers);
    }
}