package br.com.rafaelmacedo.activation_api.provider.client;

import java.util.UUID;

public record ProviderActivationRequest(
        UUID requestId,
        String externalId,
        String customerId,
        String productCode
) {
}