package br.com.rafaelmacedo.activation_api.request.domain;

public enum ActivationStatus {

    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED;

    public boolean canTransitionTo(ActivationStatus target) {
        if (target == null) {
            return false;
        }

        return switch (this) {
            case PENDING -> target == PROCESSING;
            case PROCESSING ->
                    target == COMPLETED
                            || target == FAILED
                            || target == PENDING;
            case COMPLETED, FAILED -> false;
        };
    }
}