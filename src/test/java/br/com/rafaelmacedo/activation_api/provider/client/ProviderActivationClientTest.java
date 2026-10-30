package br.com.rafaelmacedo.activation_api.provider.client;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import br.com.rafaelmacedo.activation_api.provider.config.ProviderClientConfiguration;
import br.com.rafaelmacedo.activation_api.provider.config.ProviderProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderActivationClientTest {

    private static final Instant NOW =
            Instant.parse("2026-10-30T12:00:00Z");

    private final AtomicReference<String> receivedMethod =
            new AtomicReference<>();

    private final AtomicReference<String> receivedPath =
            new AtomicReference<>();

    private final AtomicReference<String> receivedBody =
            new AtomicReference<>();

    private final AtomicReference<String> receivedIdempotencyKey =
            new AtomicReference<>();

    private final AtomicReference<String> receivedApiKey =
            new AtomicReference<>();

    private HttpServer server;
    private HttpClient httpClient;
    private ProviderActivationClient client;

    private volatile int responseStatus = 200;
    private volatile String retryAfter;
    private volatile long responseDelayMillis;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );

        server.createContext("/provider/v1/activate", exchange -> {
            try {
                receivedMethod.set(exchange.getRequestMethod());
                receivedPath.set(exchange.getRequestURI().getPath());

                receivedIdempotencyKey.set(
                        exchange.getRequestHeaders()
                                .getFirst("Idempotency-Key")
                );

                receivedApiKey.set(
                        exchange.getRequestHeaders().getFirst("X-API-Key")
                );

                receivedBody.set(new String(
                        exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8
                ));

                if (responseDelayMillis > 0) {
                    try {
                        Thread.sleep(responseDelayMillis);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                if (retryAfter != null) {
                    exchange.getResponseHeaders().set(
                            "Retry-After",
                            retryAfter
                    );
                }

                exchange.sendResponseHeaders(responseStatus, -1);
            } finally {
                exchange.close();
            }
        });

        server.start();
        configureClient(Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }

        if (httpClient != null) {
            httpClient.close();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "200, SUCCESS",
            "204, SUCCESS",
            "400, PERMANENT_FAILURE",
            "401, PERMANENT_FAILURE",
            "403, PERMANENT_FAILURE",
            "409, PERMANENT_FAILURE",
            "302, PERMANENT_FAILURE",
            "408, TEMPORARY_FAILURE",
            "429, TEMPORARY_FAILURE",
            "500, TEMPORARY_FAILURE",
            "503, TEMPORARY_FAILURE"
    })
    void shouldClassifyHttpResponse(
            int status,
            ProviderOutcome expected
    ) {
        responseStatus = status;

        ProviderActivationResult result = client.activate(newRequest());

        assertThat(result.outcome()).isEqualTo(expected);
        assertThat(result.httpStatus()).isEqualTo(status);
    }

    @Test
    void shouldSendPayloadAndAuthenticationHeaders() {
        ProviderActivationRequest request = newRequest();

        ProviderActivationResult result = client.activate(request);

        assertThat(result.outcome()).isEqualTo(ProviderOutcome.SUCCESS);
        assertThat(receivedMethod.get()).isEqualTo("POST");
        assertThat(receivedPath.get()).isEqualTo("/provider/v1/activate");

        assertThat(receivedIdempotencyKey.get())
                .isEqualTo(request.requestId().toString());

        assertThat(receivedApiKey.get()).isEqualTo("test-api-key");

        var body = JsonPath.parse(receivedBody.get());

        assertThat(body.<String>read("$.requestId"))
                .isEqualTo(request.requestId().toString());

        assertThat(body.<String>read("$.externalId"))
                .isEqualTo(request.externalId());

        assertThat(body.<String>read("$.customerId"))
                .isEqualTo(request.customerId());

        assertThat(body.<String>read("$.productCode"))
                .isEqualTo(request.productCode());
    }

    @Test
    void shouldReuseIdempotencyKeyAcrossAttempts() {
        ProviderActivationRequest request = newRequest();

        responseStatus = 500;
        ProviderActivationResult first = client.activate(request);
        String firstKey = receivedIdempotencyKey.get();

        responseStatus = 200;
        ProviderActivationResult second = client.activate(request);

        assertThat(first.outcome())
                .isEqualTo(ProviderOutcome.TEMPORARY_FAILURE);

        assertThat(second.outcome())
                .isEqualTo(ProviderOutcome.SUCCESS);

        assertThat(receivedIdempotencyKey.get()).isEqualTo(firstKey);
        assertThat(firstKey).isEqualTo(request.requestId().toString());
    }

    @Test
    void shouldReadRetryAfterSeconds() {
        responseStatus = 429;
        retryAfter = "30";

        ProviderActivationResult result = client.activate(newRequest());

        assertThat(result.outcome())
                .isEqualTo(ProviderOutcome.TEMPORARY_FAILURE);

        assertThat(result.retryAfter()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void shouldReadRetryAfterDate() {
        responseStatus = 503;

        retryAfter = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                NOW.plusSeconds(60).atZone(ZoneOffset.UTC)
        );

        ProviderActivationResult result = client.activate(newRequest());

        assertThat(result.retryAfter()).isEqualTo(NOW.plusSeconds(60));
    }

    @ParameterizedTest
    @CsvSource({
            "invalid-date",
            "-10",
            "999999999999999999999999999"
    })
    void shouldIgnoreInvalidRetryAfter(String value) {
        responseStatus = 503;
        retryAfter = value;

        ProviderActivationResult result = client.activate(newRequest());

        assertThat(result.outcome())
                .isEqualTo(ProviderOutcome.TEMPORARY_FAILURE);

        assertThat(result.retryAfter()).isNull();
    }

    @Test
    void shouldClassifyTimeoutAsTemporaryFailure() {
        configureClient(Duration.ofMillis(200));
        responseDelayMillis = 1000;

        ProviderActivationResult result = client.activate(newRequest());

        assertThat(result.outcome())
                .isEqualTo(ProviderOutcome.TEMPORARY_FAILURE);

        assertThat(result.httpStatus()).isNull();
        assertThat(result.retryAfter()).isNull();
    }

    private void configureClient(Duration readTimeout) {
        if (httpClient != null) {
            httpClient.close();
        }

        ProviderProperties properties = new ProviderProperties(
                URI.create(
                        "http://127.0.0.1:"
                                + server.getAddress().getPort()
                ),
                Duration.ofSeconds(2),
                readTimeout,
                "test-api-key"
        );

        var configuration = new ProviderClientConfiguration();

        httpClient = configuration.providerHttpClient(properties);

        var restClient = configuration.providerRestClient(
                properties,
                httpClient
        );

        client = new ProviderActivationClient(
                restClient,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private ProviderActivationRequest newRequest() {
        return new ProviderActivationRequest(
                UUID.randomUUID(),
                "EXT-12345",
                "CUST-10001",
                "INTERNET-500"
        );
    }
}