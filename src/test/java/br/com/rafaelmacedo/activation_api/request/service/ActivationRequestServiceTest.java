package br.com.rafaelmacedo.activation_api.request.service;

import br.com.rafaelmacedo.activation_api.request.domain.ActivationRequest;
import br.com.rafaelmacedo.activation_api.request.dto.CreateActivationRequest;
import br.com.rafaelmacedo.activation_api.request.exception.ActivationRequestConflictException;
import br.com.rafaelmacedo.activation_api.request.exception.ActivationRequestNotFoundException;
import br.com.rafaelmacedo.activation_api.request.repository.ActivationRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivationRequestServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-10-28T12:00:00Z");

    private static final String CONSUMER_ID = "consumer-a";

    @Mock
    private ActivationRequestRepository repository;

    private ActivationRequestService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new ActivationRequestService(repository, clock);
    }

    @Test
    void shouldCreateNewRequest() {
        when(repository.findByExternalId("EXT-12345"))
                .thenReturn(Optional.empty());

        when(repository.saveAndFlush(any(ActivationRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CreateActivationRequestResult result =
                service.create(input(), CONSUMER_ID);

        assertThat(result.created()).isTrue();
        assertThat(result.request().id()).isNotNull();
        assertThat(result.request().externalId()).isEqualTo("EXT-12345");
        assertThat(result.request().customerId()).isEqualTo("CUST-10001");
        assertThat(result.request().productCode()).isEqualTo("INTERNET-500");
        assertThat(result.request().createdAt()).isEqualTo(NOW);
        assertThat(result.request().updatedAt()).isEqualTo(NOW);
    }

    @Test
    void shouldReturnExistingRequestForIdenticalRetry() {
        ActivationRequest existing = existingRequest();

        when(repository.findByExternalId("EXT-12345"))
                .thenReturn(Optional.of(existing));

        CreateActivationRequestResult result =
                service.create(input(), CONSUMER_ID);

        assertThat(result.created()).isFalse();
        assertThat(result.request().id()).isEqualTo(existing.getId());

        verify(repository, never()).saveAndFlush(any(ActivationRequest.class));
    }

    @Test
    void shouldRejectRetryWithDifferentProduct() {
        when(repository.findByExternalId("EXT-12345"))
                .thenReturn(Optional.of(existingRequest()));

        CreateActivationRequest differentInput = new CreateActivationRequest(
                "EXT-12345",
                "CUST-10001",
                "INTERNET-1000"
        );

        assertThatThrownBy(() -> service.create(differentInput, CONSUMER_ID))
                .isInstanceOf(ActivationRequestConflictException.class);

        verify(repository, never()).saveAndFlush(any(ActivationRequest.class));
    }

    @Test
    void shouldRejectRetryWithDifferentCustomer() {
        when(repository.findByExternalId("EXT-12345"))
                .thenReturn(Optional.of(existingRequest()));

        CreateActivationRequest differentInput = new CreateActivationRequest(
                "EXT-12345",
                "CUST-20002",
                "INTERNET-500"
        );

        assertThatThrownBy(() -> service.create(differentInput, CONSUMER_ID))
                .isInstanceOf(ActivationRequestConflictException.class);
    }

    @Test
    void shouldRejectExternalIdOwnedByAnotherConsumer() {
        when(repository.findByExternalId("EXT-12345"))
                .thenReturn(Optional.of(existingRequest()));

        assertThatThrownBy(() -> service.create(input(), "consumer-b"))
                .isInstanceOf(ActivationRequestConflictException.class);
    }

    @Test
    void shouldResolveIdenticalConcurrentInsertAsRetry() {
        ActivationRequest existing = existingRequest();

        when(repository.findByExternalId("EXT-12345"))
                .thenReturn(Optional.empty(), Optional.of(existing));

        when(repository.saveAndFlush(any(ActivationRequest.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "Duplicate external ID"
                ));

        CreateActivationRequestResult result =
                service.create(input(), CONSUMER_ID);

        assertThat(result.created()).isFalse();
        assertThat(result.request().id()).isEqualTo(existing.getId());
    }

    @Test
    void shouldRejectConflictingConcurrentInsert() {
        ActivationRequest concurrentRequest = ActivationRequest.create(
                "EXT-12345",
                "CUST-10001",
                "INTERNET-1000",
                CONSUMER_ID,
                NOW
        );

        when(repository.findByExternalId("EXT-12345"))
                .thenReturn(
                        Optional.empty(),
                        Optional.of(concurrentRequest)
                );

        when(repository.saveAndFlush(any(ActivationRequest.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "Duplicate external ID"
                ));

        assertThatThrownBy(() -> service.create(input(), CONSUMER_ID))
                .isInstanceOf(ActivationRequestConflictException.class);
    }

    @Test
    void shouldPropagateIntegrityFailureWhenNoDuplicateExists() {
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException(
                        "Unexpected integrity violation"
                );

        when(repository.findByExternalId("EXT-12345"))
                .thenReturn(Optional.empty());

        when(repository.saveAndFlush(any(ActivationRequest.class)))
                .thenThrow(failure);

        assertThatThrownBy(() -> service.create(input(), CONSUMER_ID))
                .isSameAs(failure);
    }

    @Test
    void shouldFindRequestOwnedByConsumer() {
        ActivationRequest existing = existingRequest();

        when(repository.findByIdAndConsumerId(
                existing.getId(), CONSUMER_ID
        )).thenReturn(Optional.of(existing));

        var response = service.findById(existing.getId(), CONSUMER_ID);

        assertThat(response.id()).isEqualTo(existing.getId());
        assertThat(response.externalId()).isEqualTo(existing.getExternalId());
    }

    @Test
    void shouldRejectLookupWhenRequestIsNotFound() {
        UUID id = UUID.randomUUID();

        when(repository.findByIdAndConsumerId(id, CONSUMER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(id, CONSUMER_ID))
                .isInstanceOf(ActivationRequestNotFoundException.class);
    }

    @Test
    void shouldRejectLookupByAnotherConsumer() {
        ActivationRequest existing = existingRequest();

        when(repository.findByIdAndConsumerId(
                existing.getId(), "consumer-b"
        )).thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> service.findById(existing.getId(), "consumer-b")
        ).isInstanceOf(ActivationRequestNotFoundException.class);

        verify(repository).findByIdAndConsumerId(
                existing.getId(), "consumer-b"
        );
    }

    private CreateActivationRequest input() {
        return new CreateActivationRequest(
                "EXT-12345",
                "CUST-10001",
                "INTERNET-500"
        );
    }

    private ActivationRequest existingRequest() {
        return ActivationRequest.create(
                "EXT-12345",
                "CUST-10001",
                "INTERNET-500",
                CONSUMER_ID,
                NOW
        );
    }
}