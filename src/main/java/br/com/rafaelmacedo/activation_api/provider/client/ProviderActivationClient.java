package br.com.rafaelmacedo.activation_api.provider.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

@Component
public class ProviderActivationClient {

    private final RestClient restClient;
    private final Clock clock;

    public ProviderActivationClient(
            @Qualifier("providerRestClient") RestClient restClient,
            Clock clock
    ) {
        this.restClient = restClient;
        this.clock = clock;
    }

    public ProviderActivationResult activate(
            ProviderActivationRequest request
    ) {
        Objects.requireNonNull(request, "Provider request is required");
        Objects.requireNonNull(request.requestId(), "Request ID is required");

        try {
            return restClient.post()
                    .uri("/provider/v1/activate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(
                            "Idempotency-Key",
                            request.requestId().toString()
                    )
                    .body(request)
                    .exchange((httpRequest, response) -> {
                        int status = response.getStatusCode().value();

                        if (response.getStatusCode().is2xxSuccessful()) {
                            return new ProviderActivationResult(
                                    ProviderOutcome.SUCCESS,
                                    status,
                                    null
                            );
                        }

                        if (status == 408
                                || status == 429
                                || response.getStatusCode().is5xxServerError()) {
                            Instant retryAfter = null;

                            if (status == 429 || status == 503) {
                                retryAfter = parseRetryAfter(
                                        response.getHeaders()
                                                .getFirst("Retry-After")
                                );
                            }

                            return new ProviderActivationResult(
                                    ProviderOutcome.TEMPORARY_FAILURE,
                                    status,
                                    retryAfter
                            );
                        }

                        return new ProviderActivationResult(
                                ProviderOutcome.PERMANENT_FAILURE,
                                status,
                                null
                        );
                    });
        } catch (ResourceAccessException exception) {
            return new ProviderActivationResult(
                    ProviderOutcome.TEMPORARY_FAILURE,
                    null,
                    null
            );
        }
    }

    private Instant parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.trim();
        Instant now = clock.instant();

        try {
            long seconds = Long.parseLong(normalized);

            if (seconds < 0) {
                return null;
            }

            return now.plusSeconds(seconds);
        } catch (NumberFormatException exception) {
            return parseRetryAfterDate(normalized, now);
        } catch (DateTimeException | ArithmeticException exception) {
            return null;
        }
    }

    private Instant parseRetryAfterDate(String value, Instant now) {
        try {
            Instant retryAt = ZonedDateTime.parse(
                    value,
                    DateTimeFormatter.RFC_1123_DATE_TIME
            ).toInstant();

            return retryAt.isBefore(now) ? now : retryAt;
        } catch (DateTimeException exception) {
            return null;
        }
    }
}