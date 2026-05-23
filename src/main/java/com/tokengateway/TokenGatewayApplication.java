package com.tokengateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TokenGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(TokenGatewayApplication.class, args);
    }
}
