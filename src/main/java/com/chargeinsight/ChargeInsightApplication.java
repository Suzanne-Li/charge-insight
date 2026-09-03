package com.chargeinsight;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point for the independent charging operations analytics service. */
@SpringBootApplication
public class ChargeInsightApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChargeInsightApplication.class, args);
    }
}
