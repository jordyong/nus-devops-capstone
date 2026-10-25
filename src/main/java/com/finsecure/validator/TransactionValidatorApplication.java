package com.finsecure.validator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TransactionValidatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransactionValidatorApplication.class, args);
    }
}
