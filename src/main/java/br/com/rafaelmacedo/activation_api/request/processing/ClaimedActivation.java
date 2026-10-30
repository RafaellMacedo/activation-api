package br.com.rafaelmacedo.activation_api.request.processing;

import br.com.rafaelmacedo.activation_api.provider.client.ProviderActivationRequest;

public record ClaimedActivation(
        ProviderActivationRequest payload,
        int attemptCount
) {
}