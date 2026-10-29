package br.com.rafaelmacedo.activation_api.request.service;

import br.com.rafaelmacedo.activation_api.request.domain.ActivationRequest;
import br.com.rafaelmacedo.activation_api.request.dto.ActivationRequestResponse;
import br.com.rafaelmacedo.activation_api.request.dto.CreateActivationRequest;
import br.com.rafaelmacedo.activation_api.request.exception.ActivationRequestConflictException;
import br.com.rafaelmacedo.activation_api.request.exception.ActivationRequestNotFoundException;
import br.com.rafaelmacedo.activation_api.request.repository.ActivationRequestRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class ActivationRequestService {

    private final ActivationRequestRepository repository;
    private final Clock clock;

    public ActivationRequestService(
            ActivationRequestRepository repository,
            Clock clock
    ) {
        this.repository = repository;
        this.clock = clock;
    }

    public CreateActivationRequestResult create(
            CreateActivationRequest input,
            String consumerId
    ) {
        var existing = repository.findByExternalId(input.externalId());

        if (existing.isPresent()) {
            return resolveExisting(existing.get(), input, consumerId);
        }

        ActivationRequest request = ActivationRequest.create(
                input.externalId(),
                input.customerId(),
                input.productCode(),
                consumerId,
                Instant.now(clock)
        );

        try {
            ActivationRequest saved = repository.saveAndFlush(request);

            return new CreateActivationRequestResult(
                    ActivationRequestResponse.from(saved),
                    true
            );
        } catch (DataIntegrityViolationException exception) {
            // A concurrent request may have inserted the same external ID.
            ActivationRequest concurrentRequest = repository
                    .findByExternalId(input.externalId())
                    .orElseThrow(() -> exception);

            return resolveExisting(concurrentRequest, input, consumerId);
        }
    }

    public ActivationRequestResponse findById(
            UUID id,
            String consumerId
    ) {
        return repository.findByIdAndConsumerId(id, consumerId)
                .map(ActivationRequestResponse::from)
                .orElseThrow(ActivationRequestNotFoundException::new);
    }

    private CreateActivationRequestResult resolveExisting(
            ActivationRequest existing,
            CreateActivationRequest input,
            String consumerId
    ) {
        boolean sameConsumer = existing.getConsumerId().equals(consumerId);
        boolean sameCustomer = existing.getCustomerId()
                .equals(input.customerId());
        boolean sameProduct = existing.getProductCode()
                .equals(input.productCode());

        if (!sameConsumer || !sameCustomer || !sameProduct) {
            throw new ActivationRequestConflictException();
        }

        return new CreateActivationRequestResult(
                ActivationRequestResponse.from(existing),
                false
        );
    }
}