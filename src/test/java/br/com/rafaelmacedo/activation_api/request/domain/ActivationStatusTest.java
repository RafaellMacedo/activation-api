package br.com.rafaelmacedo.activation_api.request.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ActivationStatusTest {

    @Test
    void shouldAllowPendingRequestToStartProcessing() {
        assertThat(ActivationStatus.PENDING
                .canTransitionTo(ActivationStatus.PROCESSING))
                .isTrue();
    }

    @Test
    void shouldRejectSkippingProcessing() {
        assertThat(ActivationStatus.PENDING
                .canTransitionTo(ActivationStatus.COMPLETED))
                .isFalse();
    }

    @Test
    void shouldAllowProcessingRequestToBeRescheduled() {
        assertThat(ActivationStatus.PROCESSING
                .canTransitionTo(ActivationStatus.PENDING))
                .isTrue();
    }

    @Test
    void shouldAllowProcessingRequestToCompleteOrFail() {
        assertThat(ActivationStatus.PROCESSING
                .canTransitionTo(ActivationStatus.COMPLETED))
                .isTrue();

        assertThat(ActivationStatus.PROCESSING
                .canTransitionTo(ActivationStatus.FAILED))
                .isTrue();
    }

    @Test
    void shouldRejectTransitionsFromTerminalStates() {
        for (ActivationStatus target : ActivationStatus.values()) {
            assertThat(ActivationStatus.COMPLETED.canTransitionTo(target))
                    .isFalse();

            assertThat(ActivationStatus.FAILED.canTransitionTo(target))
                    .isFalse();
        }
    }

    @Test
    void shouldRejectTransitionToSameStatus() {
        for (ActivationStatus status : ActivationStatus.values()) {
            assertThat(status.canTransitionTo(status))
                    .isFalse();
        }
    }

    @Test
    void shouldRejectNullTarget() {
        assertThat(ActivationStatus.PENDING.canTransitionTo(null))
                .isFalse();
    }
}