package ru.tinkoff.invest.emulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TinkoffBrokerEmulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(TinkoffBrokerEmulatorApplication.class, args);
    }

}
