package br.com.rafaelmacedo.activation_api.request.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateActivationRequest(

    @NotBlank(message = "External ID is required")
    @Size(max = 100, message = "External ID must not exceed 100 characters")
    String externalId,

    @NotBlank(message = "Customer ID is required")
    @Size(max = 100, message = "Customer ID must not exceed 100 characters")
    String customerId,

    @NotBlank(message = "Product code is required")
    @Size(max = 100, message = "Product code must not exceed 100 characters")
    String productCode

) {
}