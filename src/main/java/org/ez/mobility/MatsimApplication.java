package org.ez.mobility;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.ez.mobility.ez.PolicyEngine.PolicyConfigurationException;
import org.ez.mobility.ez.TransitConfigGroup.TransitConfigurationException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashMap;
import java.util.Map;

@SpringBootApplication
@EnableScheduling
@EnableCaching
@EnableConfigurationProperties
@ComponentScan(basePackages = {
    "org.ez.mobility.api", 
    "org.ez.mobility.core",
    "org.ez.mobility.core.service", 
    "org.ez.mobility.ez", 
    "org.ez.mobility.output",
    "org.ez.mobility.config"
})
public class MatsimApplication {
    public static void main(String[] args) {
        SpringApplication.run(MatsimApplication.class, args);
    }

    @ControllerAdvice
    public static class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
        private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

        @ExceptionHandler(PolicyConfigurationException.class)
        public ResponseEntity<Object> handlePolicyConfigurationException(PolicyConfigurationException ex) {
            logger.error("Policy configuration error", ex);
            return buildErrorResponse(HttpStatus.BAD_REQUEST, "Policy Configuration Error", ex.getMessage());
        }

        @ExceptionHandler(TransitConfigurationException.class)
        public ResponseEntity<Object> handleTransitConfigurationException(TransitConfigurationException ex) {
            logger.error("Transit configuration error", ex);
            return buildErrorResponse(HttpStatus.BAD_REQUEST, "Transit Configuration Error", ex.getMessage());
        }

        @ExceptionHandler(ConstraintViolationException.class)
        public ResponseEntity<Object> handleConstraintViolationException(ConstraintViolationException ex) {
            logger.error("Validation error", ex);
            return buildErrorResponse(HttpStatus.BAD_REQUEST, "Validation Error", ex.getMessage());
        }

        @ExceptionHandler(IllegalArgumentException.class)
        public ResponseEntity<Object> handleIllegalArgumentException(IllegalArgumentException ex) {
            logger.error("Invalid argument error", ex);
            return buildErrorResponse(HttpStatus.BAD_REQUEST, "Invalid Argument", ex.getMessage());
        }

        @ExceptionHandler(IllegalStateException.class)
        public ResponseEntity<Object> handleIllegalStateException(IllegalStateException ex) {
            logger.error("Invalid state error", ex);
            return buildErrorResponse(HttpStatus.BAD_REQUEST, "Invalid State", ex.getMessage());
        }

        @ExceptionHandler(Exception.class)
        public ResponseEntity<Object> handleGenericException(Exception ex) {
            logger.error("Unexpected error", ex);
            return buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                "An unexpected error occurred. Please try again later."
            );
        }

        private ResponseEntity<Object> buildErrorResponse(
                HttpStatus status, String error, String message) {
            Map<String, Object> body = new HashMap<>();
            body.put("status", status.value());
            body.put("error", error);
            body.put("message", message);
            body.put("timestamp", System.currentTimeMillis());
            return new ResponseEntity<>(body, status);
        }
    }
}
