package br.com.rafaelmacedo.activation_api.request.dto;

import br.com.rafaelmacedo.activation_api.request.domain.ActivationRequest;
import br.com.rafaelmacedo.activation_api.request.domain.ActivationStatus;

import java.time.Instant;
import java.util.UUID;

public record ActivationRequestResponse(
    UUID id,
    String externalId,
    String customerId,
    String productCode,
    ActivationStatus status,
    Instant createdAt,
    Instant updatedAt
) {

    public static ActivationRequestResponse from(ActivationRequest request) {
        return new ActivationRequestResponse(
            request.getId(),
            request.getExternalId(),
            request.getCustomerId(),
            request.getProductCode(),
            request.getStatus(),
            request.getCreatedAt(),
            request.getUpdatedAt()
        );
    }
}