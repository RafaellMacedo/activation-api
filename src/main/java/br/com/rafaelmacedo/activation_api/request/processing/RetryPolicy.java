package br.com.rafaelmacedo.activation_api.request.processing;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Component
public class RetryPolicy {

    private final ProcessingProperties properties;
    private final Clock clock;

    public RetryPolicy(
            ProcessingProperties properties,
            Clock clock
    ) {
        this.properties = properties;
        this.clock = clock;
    }

    public Optional<Instant> nextAttemptAt(
            int completedAttempts,
            Instant providerRetryAfter
    ) {
        if (completedAttempts < 1) {
            throw new IllegalArgumentException(
                    "Completed attempts must be at least 1"
            );
        }

        if (completedAttempts >= properties.maxAttempts()) {
            return Optional.empty();
        }

        Instant retryAt = clock.instant()
                .plus(backoffFor(completedAttempts));

        if (providerRetryAfter != null
                && providerRetryAfter.isAfter(retryAt)) {
            retryAt = providerRetryAfter;
        }

        return Optional.of(retryAt);
    }

    private Duration backoffFor(int completedAttempts) {
        Duration delay = properties.initialBackoff();
        Duration maximum = properties.maxBackoff();

        for (int attempt = 1;
                attempt < completedAttempts && delay.compareTo(maximum) < 0;
                attempt++) {

            try {
                delay = delay.multipliedBy(2);
            } catch (ArithmeticException exception) {
                return maximum;
            }

            if (delay.compareTo(maximum) > 0) {
                return maximum;
            }
        }

        return delay;
    }
}