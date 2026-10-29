package br.com.rafaelmacedo.activation_api.request.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "activation_requests")
public class ActivationRequest {

    @Id
    private UUID id;

    @Column(name = "external_id", nullable = false, length = 100)
    private String externalId;

    @Column(name = "customer_id", nullable = false, length = 100)
    private String customerId;

    @Column(name = "product_code", nullable = false, length = 100)
    private String productCode;

    @Column(name = "consumer_id", nullable = false, length = 100)
    private String consumerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActivationStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected ActivationRequest() {
        // Required by JPA.
    }

    public static ActivationRequest create(
            String externalId,
            String customerId,
            String productCode,
            String consumerId,
            Instant now
    ) {
        Objects.requireNonNull(now, "Creation time is required");

        ActivationRequest request = new ActivationRequest();
        request.id = UUID.randomUUID();
        request.externalId = requireIdentifier(externalId, "External ID");
        request.customerId = requireIdentifier(customerId, "Customer ID");
        request.productCode = requireIdentifier(productCode, "Product code");
        request.consumerId = requireIdentifier(consumerId, "Consumer ID");
        request.status = ActivationStatus.PENDING;
        request.attemptCount = 0;
        request.nextAttemptAt = now;
        request.createdAt = now;
        request.updatedAt = now;

        return request;
    }

    public void startProcessing(Instant now) {
        Objects.requireNonNull(now, "Processing time is required");

        if (nextAttemptAt != null && now.isBefore(nextAttemptAt)) {
            throw new IllegalStateException("Request is not due for processing");
        }

        transitionTo(ActivationStatus.PROCESSING, now);
        attemptCount++;
        processingStartedAt = now;
        nextAttemptAt = null;
    }

    public void complete(Instant now) {
        transitionTo(ActivationStatus.COMPLETED, now);
        processingStartedAt = null;
        nextAttemptAt = null;
    }

    public void fail(Instant now) {
        transitionTo(ActivationStatus.FAILED, now);
        processingStartedAt = null;
        nextAttemptAt = null;
    }

    public void scheduleRetry(Instant nextAttemptAt, Instant now) {
        Objects.requireNonNull(now, "Current time is required");
        Objects.requireNonNull(nextAttemptAt, "Next attempt time is required");

        if (!nextAttemptAt.isAfter(now)) {
            throw new IllegalArgumentException(
                    "Next attempt time must be in the future"
            );
        }

        transitionTo(ActivationStatus.PENDING, now);
        this.nextAttemptAt = nextAttemptAt;
        processingStartedAt = null;
    }

    private void transitionTo(ActivationStatus target, Instant now) {
        Objects.requireNonNull(now, "Transition time is required");

        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Cannot transition from " + status + " to " + target
            );
        }

        if (now.isBefore(updatedAt)) {
            throw new IllegalArgumentException(
                    "Transition time must not precede the last update"
            );
        }

        status = target;
        updatedAt = now;
    }

    private static String requireIdentifier(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 100) {
            throw new IllegalArgumentException(
                    field + " must contain between 1 and 100 characters"
            );
        }

        return value;
    }

    public UUID getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getProductCode() {
        return productCode;
    }

    public String getConsumerId() {
        return consumerId;
    }

    public ActivationStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getProcessingStartedAt() {
        return processingStartedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}