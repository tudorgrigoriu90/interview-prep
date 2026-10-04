package com.example.cashier;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CashierApplication {

    public static void main(String[] args) {
        SpringApplication.run(CashierApplication.class, args);
    }
}
