package br.com.rafaelmacedo.activation_api.request.controller;

import br.com.rafaelmacedo.activation_api.request.dto.ActivationRequestResponse;
import br.com.rafaelmacedo.activation_api.request.dto.CreateActivationRequest;
import br.com.rafaelmacedo.activation_api.request.service.ActivationRequestService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/requests")
public class ActivationRequestController {

    private final ActivationRequestService service;

    public ActivationRequestController(ActivationRequestService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ActivationRequestResponse> create(
            @Valid @RequestBody CreateActivationRequest input,
            @AuthenticationPrincipal Jwt jwt
    ) {
        var result = service.create(input, jwt.getSubject());

        if (!result.created()) {
            return ResponseEntity.ok(result.request());
        }

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(result.request().id())
                .toUri();

        return ResponseEntity.created(location).body(result.request());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ActivationRequestResponse> findById(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt
    ) {
        var response = service.findById(id, jwt.getSubject());

        return ResponseEntity.ok(response);
    }
}