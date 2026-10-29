package br.com.rafaelmacedo.activation_api.request.service;

import br.com.rafaelmacedo.activation_api.request.dto.ActivationRequestResponse;

public record CreateActivationRequestResult(
        ActivationRequestResponse request,
        boolean created
) {
}