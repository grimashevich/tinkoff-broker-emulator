package ru.tinkoff.invest.emulator.config;

import net.devh.boot.grpc.server.interceptor.GlobalServerInterceptorConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.tinkoff.invest.emulator.grpc.interceptor.AuthInterceptor;

@Configuration
public class GrpcConfig {

    @Bean
    public GlobalServerInterceptorConfigurer globalInterceptorConfigurerAdapter(AuthInterceptor authInterceptor) {
        return registry -> registry.add(authInterceptor);
    }
}
