package br.com.rafaelmacedo.activation_api;

import br.com.rafaelmacedo.activation_api.request.domain.ActivationRequest;
import br.com.rafaelmacedo.activation_api.request.domain.ActivationStatus;
import br.com.rafaelmacedo.activation_api.request.processing.ActivationProcessor;
import br.com.rafaelmacedo.activation_api.request.repository.ActivationRequestRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "app.processing.enabled=false",
                "app.processing.max-attempts=3",
                "app.processing.initial-backoff=2s",
                "app.processing.max-backoff=1m"
        }
)
@Import(TestcontainersConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ActivationProcessingTest {

    private static final Instant NOW =
            Instant.parse("2026-10-30T12:00:00Z");

    private static final AtomicInteger RESPONSE_STATUS =
            new AtomicInteger(200);

    private static final AtomicInteger PROVIDER_CALLS =
            new AtomicInteger();

    private static final List<String> IDEMPOTENCY_KEYS =
            new CopyOnWriteArrayList<>();

    private static final HttpServer PROVIDER = startProvider();

    private final AtomicReference<Instant> currentTime =
            new AtomicReference<>(NOW);

    @Autowired
    private ActivationRequestRepository repository;

    @Autowired
    private ActivationProcessor processor;

    @MockitoBean
    private Clock clock;

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "app.provider.base-url",
                () -> "http://127.0.0.1:"
                        + PROVIDER.getAddress().getPort()
        );
    }

    @BeforeEach
    void setUp() {
        repository.deleteAllInBatch();

        currentTime.set(NOW);
        RESPONSE_STATUS.set(200);
        PROVIDER_CALLS.set(0);
        IDEMPOTENCY_KEYS.clear();

        when(clock.instant()).thenAnswer(
                invocation -> currentTime.get()
        );
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.stop(0);
    }

    @Test
    void shouldCompleteRequestWhenProviderSucceeds() {
        UUID id = saveRequest();

        processor.process(id);

        ActivationRequest request = findRequest(id);

        assertThat(request.getStatus())
                .isEqualTo(ActivationStatus.COMPLETED);

        assertThat(request.getAttemptCount()).isEqualTo(1);
        assertThat(request.getProcessingStartedAt()).isNull();
        assertThat(request.getNextAttemptAt()).isNull();
        assertThat(PROVIDER_CALLS.get()).isEqualTo(1);
    }

    @Test
    void shouldFailRequestWhenProviderRejectsIt() {
        RESPONSE_STATUS.set(400);
        UUID id = saveRequest();

        processor.process(id);

        ActivationRequest request = findRequest(id);

        assertThat(request.getStatus())
                .isEqualTo(ActivationStatus.FAILED);

        assertThat(request.getAttemptCount()).isEqualTo(1);
        assertThat(request.getNextAttemptAt()).isNull();
        assertThat(PROVIDER_CALLS.get()).isEqualTo(1);
    }

    @Test
    void shouldRetryTemporaryFailureAndThenComplete() {
        RESPONSE_STATUS.set(500);
        UUID id = saveRequest();

        processor.process(id);

        ActivationRequest pending = findRequest(id);

        assertThat(pending.getStatus())
                .isEqualTo(ActivationStatus.PENDING);

        assertThat(pending.getAttemptCount()).isEqualTo(1);
        assertThat(pending.getNextAttemptAt())
                .isEqualTo(NOW.plusSeconds(2));

        currentTime.set(pending.getNextAttemptAt());
        RESPONSE_STATUS.set(200);

        processor.process(id);

        ActivationRequest completed = findRequest(id);

        assertThat(completed.getStatus())
                .isEqualTo(ActivationStatus.COMPLETED);

        assertThat(completed.getAttemptCount()).isEqualTo(2);
        assertThat(PROVIDER_CALLS.get()).isEqualTo(2);

        assertThat(IDEMPOTENCY_KEYS).containsExactly(
                id.toString(),
                id.toString()
        );
    }

    @Test
    void shouldFailAfterMaximumAttempts() {
        RESPONSE_STATUS.set(500);
        UUID id = saveRequest();

        for (int attempt = 1; attempt <= 3; attempt++) {
            processor.process(id);

            ActivationRequest request = findRequest(id);

            if (attempt < 3) {
                assertThat(request.getStatus())
                        .isEqualTo(ActivationStatus.PENDING);

                currentTime.set(request.getNextAttemptAt());
            }
        }

        ActivationRequest failed = findRequest(id);

        assertThat(failed.getStatus())
                .isEqualTo(ActivationStatus.FAILED);

        assertThat(failed.getAttemptCount()).isEqualTo(3);
        assertThat(failed.getNextAttemptAt()).isNull();
        assertThat(PROVIDER_CALLS.get()).isEqualTo(3);

        processor.process(id);

        assertThat(PROVIDER_CALLS.get()).isEqualTo(3);
    }

    @Test
    void shouldNotRetryBeforeScheduledTime() {
        RESPONSE_STATUS.set(500);
        UUID id = saveRequest();

        processor.process(id);
        processor.process(id);

        ActivationRequest request = findRequest(id);

        assertThat(request.getStatus())
                .isEqualTo(ActivationStatus.PENDING);

        assertThat(request.getAttemptCount()).isEqualTo(1);
        assertThat(PROVIDER_CALLS.get()).isEqualTo(1);
    }

    private UUID saveRequest() {
        return repository.saveAndFlush(ActivationRequest.create(
                "EXT-12345",
                "CUST-10001",
                "INTERNET-500",
                "consumer-a",
                currentTime.get()
        )).getId();
    }

    private ActivationRequest findRequest(UUID id) {
        return repository.findById(id).orElseThrow();
    }

    private static HttpServer startProvider() {
        try {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0),
                    0
            );

            server.createContext("/provider/v1/activate", exchange -> {
                try {
                    exchange.getRequestBody().readAllBytes();

                    IDEMPOTENCY_KEYS.add(
                            exchange.getRequestHeaders()
                                    .getFirst("Idempotency-Key")
                    );

                    PROVIDER_CALLS.incrementAndGet();

                    exchange.sendResponseHeaders(
                            RESPONSE_STATUS.get(),
                            -1
                    );
                } finally {
                    exchange.close();
                }
            });

            server.start();
            return server;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not start test provider",
                    exception
            );
        }
    }
}