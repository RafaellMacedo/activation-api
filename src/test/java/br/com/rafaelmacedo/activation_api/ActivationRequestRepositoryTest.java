package br.com.rafaelmacedo.activation_api;

import br.com.rafaelmacedo.activation_api.request.domain.ActivationRequest;
import br.com.rafaelmacedo.activation_api.request.domain.ActivationStatus;
import br.com.rafaelmacedo.activation_api.request.repository.ActivationRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class ActivationRequestRepositoryTest {

    private static final Instant NOW =
            Instant.parse("2026-10-28T12:00:00Z");

    @Autowired
    private ActivationRequestRepository repository;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAllInBatch();
    }

    @Test
    void shouldPersistAndRetrieveRequest() {
        ActivationRequest saved = repository.saveAndFlush(newRequest());

        ActivationRequest found = repository.findById(saved.getId())
                .orElseThrow();

        assertThat(found.getExternalId()).isEqualTo("EXT-12345");
        assertThat(found.getCustomerId()).isEqualTo("CUST-10001");
        assertThat(found.getProductCode()).isEqualTo("INTERNET-500");
        assertThat(found.getConsumerId()).isEqualTo("consumer-a");
        assertThat(found.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(found.getAttemptCount()).isZero();
        assertThat(found.getCreatedAt()).isEqualTo(NOW);
        assertThat(found.getUpdatedAt()).isEqualTo(NOW);
        assertThat(found.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(found.getVersion()).isNotNull();
    }

    @Test
    void shouldFindRequestByExternalId() {
        ActivationRequest saved = repository.saveAndFlush(newRequest());

        assertThat(repository.findByExternalId("EXT-12345"))
                .hasValueSatisfying(found ->
                        assertThat(found.getId()).isEqualTo(saved.getId())
                );
    }

    @Test
    void shouldRejectDuplicateExternalId() {
        repository.saveAndFlush(newRequest());

        assertThatThrownBy(() -> repository.saveAndFlush(newRequest()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void shouldRestrictLookupToOwningConsumer() {
        ActivationRequest saved = repository.saveAndFlush(newRequest());

        assertThat(repository.findByIdAndConsumerId(
                saved.getId(), "consumer-a"
        )).isPresent();

        assertThat(repository.findByIdAndConsumerId(
                saved.getId(), "consumer-b"
        )).isEmpty();
    }

    @Test
    void shouldRejectUpdateUsingStaleVersion() {
        ActivationRequest saved = repository.saveAndFlush(newRequest());

        ActivationRequest firstCopy = repository.findById(saved.getId())
                .orElseThrow();

        ActivationRequest staleCopy = repository.findById(saved.getId())
                .orElseThrow();

        firstCopy.startProcessing(NOW.plusSeconds(1));
        repository.saveAndFlush(firstCopy);

        staleCopy.startProcessing(NOW.plusSeconds(2));

        assertThatThrownBy(() -> repository.saveAndFlush(staleCopy))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        ActivationRequest found = repository.findById(saved.getId())
                .orElseThrow();

        assertThat(found.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
        assertThat(found.getAttemptCount()).isEqualTo(1);
        assertThat(found.getProcessingStartedAt())
                .isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void shouldPersistExactlyOneRequestWhenConcurrentDuplicatesArrive()
            throws Exception {

        int concurrentRequests = 8;

        CountDownLatch ready = new CountDownLatch(concurrentRequests);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Boolean>> results = new ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(concurrentRequests)) {
            try {
                for (int index = 0; index < concurrentRequests; index++) {
                    results.add(executor.submit(() -> {
                        ready.countDown();

                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "Timed out waiting for concurrent start"
                            );
                        }

                        try {
                            repository.saveAndFlush(newRequest());
                            return true;
                        } catch (DataIntegrityViolationException exception) {
                            return false;
                        }
                    }));
                }

                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }

            int successfulInserts = 0;
            int rejectedInserts = 0;

            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    successfulInserts++;
                } else {
                    rejectedInserts++;
                }
            }

            assertThat(successfulInserts).isEqualTo(1);
            assertThat(rejectedInserts).isEqualTo(concurrentRequests - 1);
            assertThat(repository.count()).isEqualTo(1);
            assertThat(repository.findByExternalId("EXT-12345")).isPresent();
        }
    }

    private ActivationRequest newRequest() {
        return ActivationRequest.create(
                "EXT-12345",
                "CUST-10001",
                "INTERNET-500",
                "consumer-a",
                NOW
        );
    }
}