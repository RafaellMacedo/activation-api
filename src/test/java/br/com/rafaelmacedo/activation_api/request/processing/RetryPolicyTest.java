package br.com.rafaelmacedo.activation_api.request.processing;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryPolicyTest {

    private static final Instant NOW =
            Instant.parse("2026-10-30T12:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void shouldScheduleFirstRetryUsingInitialBackoff() {
        RetryPolicy policy = newPolicy(3);

        assertThat(policy.nextAttemptAt(1, null))
                .contains(NOW.plusSeconds(2));
    }

    @Test
    void shouldDoubleBackoffForSecondRetry() {
        RetryPolicy policy = newPolicy(3);

        assertThat(policy.nextAttemptAt(2, null))
                .contains(NOW.plusSeconds(4));
    }

    @Test
    void shouldStopWhenMaximumAttemptsIsReached() {
        RetryPolicy policy = newPolicy(3);

        assertThat(policy.nextAttemptAt(3, null)).isEmpty();
    }

    @Test
    void shouldStopWhenMaximumAttemptsIsExceeded() {
        RetryPolicy policy = newPolicy(3);

        assertThat(policy.nextAttemptAt(4, null)).isEmpty();
    }

    @Test
    void shouldNotRetryWhenOnlyOneAttemptIsAllowed() {
        RetryPolicy policy = newPolicy(1);

        assertThat(policy.nextAttemptAt(1, null)).isEmpty();
    }

    @Test
    void shouldCapExponentialBackoff() {
        RetryPolicy policy = newPolicy(10);

        assertThat(policy.nextAttemptAt(7, null))
                .contains(NOW.plusSeconds(60));
    }

    @Test
    void shouldHonorProviderRetryAfterWhenLaterThanLocalBackoff() {
        RetryPolicy policy = newPolicy(3);
        Instant providerRetryAfter = NOW.plusSeconds(30);

        assertThat(policy.nextAttemptAt(1, providerRetryAfter))
                .contains(providerRetryAfter);
    }

    @Test
    void shouldKeepLocalBackoffWhenProviderRequestsEarlierRetry() {
        RetryPolicy policy = newPolicy(3);

        assertThat(policy.nextAttemptAt(1, NOW.plusSeconds(1)))
                .contains(NOW.plusSeconds(2));
    }

    @Test
    void shouldIgnorePastProviderRetryAfter() {
        RetryPolicy policy = newPolicy(3);

        assertThat(policy.nextAttemptAt(1, NOW.minusSeconds(30)))
                .contains(NOW.plusSeconds(2));
    }

    @Test
    void shouldHonorProviderRetryAfterBeyondLocalBackoffLimit() {
        RetryPolicy policy = newPolicy(10);
        Instant providerRetryAfter = NOW.plusSeconds(120);

        assertThat(policy.nextAttemptAt(7, providerRetryAfter))
                .contains(providerRetryAfter);
    }

    @Test
    void shouldNotRetryAfterLimitEvenWhenProviderSuppliesRetryAfter() {
        RetryPolicy policy = newPolicy(3);

        assertThat(policy.nextAttemptAt(3, NOW.plusSeconds(30)))
                .isEmpty();
    }

    @Test
    void shouldRejectInvalidAttemptCount() {
        RetryPolicy policy = newPolicy(3);

        assertThatThrownBy(() -> policy.nextAttemptAt(0, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> policy.nextAttemptAt(-1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RetryPolicy newPolicy(int maxAttempts) {
        return new RetryPolicy(
                new ProcessingProperties(
                        maxAttempts,
                        Duration.ofSeconds(2),
                        Duration.ofMinutes(1)
                ),
                clock
        );
    }
}