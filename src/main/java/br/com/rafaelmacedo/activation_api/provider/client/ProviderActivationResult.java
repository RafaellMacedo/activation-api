package br.com.rafaelmacedo.activation_api.provider.client;

import java.time.Instant;

public record ProviderActivationResult(
        ProviderOutcome outcome,
        Integer httpStatus,
        Instant retryAfter
) {
}