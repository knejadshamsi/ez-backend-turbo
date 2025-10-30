package org.mobility;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableAsync
@EnableTransactionManagement
public class MatsimApplication {
    public static void main(String[] args) {
        try {
            SpringApplication.run(MatsimApplication.class, args);
        } catch (Exception e) {
            System.err.println("Application failed to start: " + e.getMessage());
            System.exit(1);
        }
    }
}
