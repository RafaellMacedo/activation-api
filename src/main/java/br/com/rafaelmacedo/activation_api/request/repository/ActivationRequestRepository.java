package br.com.rafaelmacedo.activation_api.request.repository;

import br.com.rafaelmacedo.activation_api.request.domain.ActivationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import br.com.rafaelmacedo.activation_api.request.domain.ActivationStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ActivationRequestRepository extends JpaRepository<ActivationRequest, UUID> {

    Optional<ActivationRequest> findByExternalId(String externalId);

    Optional<ActivationRequest> findByIdAndConsumerId(
            UUID id,
            String consumerId
    );

    List<ActivationRequest>
        findTop10ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                ActivationStatus status,
                Instant now
        );
}