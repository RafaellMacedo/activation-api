package br.com.rafaelmacedo.activation_api.request.processing;

import br.com.rafaelmacedo.activation_api.provider.client.ProviderActivationRequest;
import br.com.rafaelmacedo.activation_api.provider.client.ProviderActivationResult;
import br.com.rafaelmacedo.activation_api.request.domain.ActivationRequest;
import br.com.rafaelmacedo.activation_api.request.domain.ActivationStatus;
import br.com.rafaelmacedo.activation_api.request.repository.ActivationRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class ActivationProcessingTransactions {

    private final ActivationRequestRepository repository;
    private final RetryPolicy retryPolicy;
    private final Clock clock;

    public ActivationProcessingTransactions(
            ActivationRequestRepository repository,
            RetryPolicy retryPolicy,
            Clock clock
    ) {
        this.repository = repository;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
    }

    @Transactional
    public Optional<ClaimedActivation> claim(UUID requestId) {
        Optional<ActivationRequest> found = repository.findById(requestId);

        if (found.isEmpty()) {
            return Optional.empty();
        }

        ActivationRequest request = found.get();
        Instant now = clock.instant();

        if (request.getStatus() != ActivationStatus.PENDING) {
            return Optional.empty();
        }

        if (request.getNextAttemptAt() != null
                && request.getNextAttemptAt().isAfter(now)) {
            return Optional.empty();
        }

        request.startProcessing(now);
        repository.saveAndFlush(request);

        ProviderActivationRequest payload = new ProviderActivationRequest(
                request.getId(),
                request.getExternalId(),
                request.getCustomerId(),
                request.getProductCode()
        );

        return Optional.of(new ClaimedActivation(
                payload,
                request.getAttemptCount()
        ));
    }

    @Transactional
    public boolean applyResult(
            ClaimedActivation claim,
            ProviderActivationResult result
    ) {
        Optional<ActivationRequest> found = repository.findById(
                claim.payload().requestId()
        );

        if (found.isEmpty()) {
            return false;
        }

        ActivationRequest request = found.get();

        if (request.getStatus() != ActivationStatus.PROCESSING
                || request.getAttemptCount() != claim.attemptCount()) {
            return false;
        }

        Instant now = clock.instant();

        switch (result.outcome()) {
            case SUCCESS -> request.complete(now);
            case PERMANENT_FAILURE -> request.fail(now);
            case TEMPORARY_FAILURE -> {
                Optional<Instant> nextAttemptAt = retryPolicy.nextAttemptAt(
                        request.getAttemptCount(),
                        result.retryAfter()
                );

                if (nextAttemptAt.isPresent()) {
                    request.scheduleRetry(nextAttemptAt.get(), now);
                } else {
                    request.fail(now);
                }
            }
        }

        repository.saveAndFlush(request);
        return true;
    }
}