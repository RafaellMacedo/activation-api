package br.com.rafaelmacedo.activation_api.request.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActivationRequestTest {

    private static final Instant NOW =
            Instant.parse("2026-10-28T12:00:00Z");

    @Test
    void shouldCreatePendingRequest() {
        ActivationRequest request = newRequest();

        assertThat(request.getId()).isNotNull();
        assertThat(request.getExternalId()).isEqualTo("EXT-12345");
        assertThat(request.getCustomerId()).isEqualTo("CUST-10001");
        assertThat(request.getProductCode()).isEqualTo("INTERNET-500");
        assertThat(request.getConsumerId()).isEqualTo("consumer-a");
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getAttemptCount()).isZero();
        assertThat(request.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(request.getProcessingStartedAt()).isNull();
        assertThat(request.getCreatedAt()).isEqualTo(NOW);
        assertThat(request.getUpdatedAt()).isEqualTo(NOW);
        assertThat(request.getVersion()).isNull();
    }

    @Test
    void shouldStartProcessingAndCountAttempt() {
        ActivationRequest request = newRequest();
        Instant startedAt = NOW.plusSeconds(1);

        request.startProcessing(startedAt);

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
        assertThat(request.getAttemptCount()).isEqualTo(1);
        assertThat(request.getProcessingStartedAt()).isEqualTo(startedAt);
        assertThat(request.getNextAttemptAt()).isNull();
        assertThat(request.getUpdatedAt()).isEqualTo(startedAt);
    }

    @Test
    void shouldCompleteProcessingRequest() {
        ActivationRequest request = newRequest();
        request.startProcessing(NOW);

        Instant completedAt = NOW.plusSeconds(2);
        request.complete(completedAt);

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(request.getUpdatedAt()).isEqualTo(completedAt);
        assertThat(request.getProcessingStartedAt()).isNull();
        assertThat(request.getNextAttemptAt()).isNull();
        assertThat(request.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void shouldFailProcessingRequest() {
        ActivationRequest request = newRequest();
        request.startProcessing(NOW);

        request.fail(NOW.plusSeconds(2));

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(request.getProcessingStartedAt()).isNull();
        assertThat(request.getNextAttemptAt()).isNull();
    }

    @Test
    void shouldScheduleRetryAndStartWhenDue() {
        ActivationRequest request = newRequest();
        request.startProcessing(NOW);

        Instant retryAt = NOW.plusSeconds(30);
        request.scheduleRetry(retryAt, NOW.plusSeconds(1));

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getNextAttemptAt()).isEqualTo(retryAt);
        assertThat(request.getProcessingStartedAt()).isNull();
        assertThat(request.getAttemptCount()).isEqualTo(1);

        request.startProcessing(retryAt);

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
        assertThat(request.getAttemptCount()).isEqualTo(2);
        assertThat(request.getNextAttemptAt()).isNull();
    }

    @Test
    void shouldRejectProcessingBeforeRetryIsDue() {
        ActivationRequest request = newRequest();
        request.startProcessing(NOW);

        Instant retryAt = NOW.plusSeconds(30);
        request.scheduleRetry(retryAt, NOW.plusSeconds(1));

        assertThatThrownBy(
                () -> request.startProcessing(retryAt.minusSeconds(1))
        ).isInstanceOf(IllegalStateException.class);

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getAttemptCount()).isEqualTo(1);
        assertThat(request.getNextAttemptAt()).isEqualTo(retryAt);
    }

    @Test
    void shouldRejectCompletionBeforeProcessing() {
        ActivationRequest request = newRequest();

        assertThatThrownBy(() -> request.complete(NOW))
                .isInstanceOf(IllegalStateException.class);

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
    }

    @Test
    void shouldRejectReprocessingCompletedRequest() {
        ActivationRequest request = newRequest();
        request.startProcessing(NOW);
        request.complete(NOW.plusSeconds(1));

        assertThatThrownBy(
                () -> request.startProcessing(NOW.plusSeconds(2))
        ).isInstanceOf(IllegalStateException.class);

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(request.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void shouldRejectRetryForFailedRequest() {
        ActivationRequest request = newRequest();
        request.startProcessing(NOW);
        request.fail(NOW.plusSeconds(1));

        assertThatThrownBy(
                () -> request.scheduleRetry(
                        NOW.plusSeconds(30),
                        NOW.plusSeconds(2)
                )
        ).isInstanceOf(IllegalStateException.class);

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(request.getNextAttemptAt()).isNull();
    }

    @Test
    void shouldRejectRetryWithoutFutureSchedule() {
        ActivationRequest request = newRequest();
        request.startProcessing(NOW);

        assertThatThrownBy(() -> request.scheduleRetry(NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
        assertThat(request.getNextAttemptAt()).isNull();
    }

    @Test
    void shouldRejectTransitionBeforeLastUpdate() {
        ActivationRequest request = newRequest();
        Instant startedAt = NOW.plusSeconds(5);
        request.startProcessing(startedAt);

        assertThatThrownBy(() -> request.complete(NOW))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
        assertThat(request.getUpdatedAt()).isEqualTo(startedAt);
    }

    @Test
    void shouldRejectBlankExternalId() {
        assertThatThrownBy(() -> ActivationRequest.create(
                " ",
                "CUST-10001",
                "INTERNET-500",
                "consumer-a",
                NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectIdentifierExceedingMaximumLength() {
        assertThatThrownBy(() -> ActivationRequest.create(
                "A".repeat(101),
                "CUST-10001",
                "INTERNET-500",
                "consumer-a",
                NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private ActivationRequest newRequest() {
        return ActivationRequest.create(
                "EXT-12345",
                "CUST-10001",
                "INTERNET-500",
                "consumer-a",
                NOW
        );
    }
}