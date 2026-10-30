package br.com.rafaelmacedo.activation_api.request.processing;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.processing")
public record ProcessingProperties(
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff
) {

    public ProcessingProperties {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException(
                    "Maximum attempts must be at least 1"
            );
        }

        requirePositive(initialBackoff, "Initial backoff");
        requirePositive(maxBackoff, "Maximum backoff");

        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException(
                    "Maximum backoff must not be less than initial backoff"
            );
        }
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}