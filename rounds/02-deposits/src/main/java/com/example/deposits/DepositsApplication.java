package com.example.deposits;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DepositsApplication {

    public static void main(String[] args) {
        SpringApplication.run(DepositsApplication.class, args);
    }
}
