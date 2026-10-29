package br.com.rafaelmacedo.activation_api;

import br.com.rafaelmacedo.activation_api.request.domain.ActivationRequest;
import br.com.rafaelmacedo.activation_api.request.repository.ActivationRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ActivationRequestApiTest {

    private static final String REQUEST_BODY = """
            {
              "externalId": "EXT-12345",
              "customerId": "CUST-10001",
              "productCode": "INTERNET-500"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ActivationRequestRepository repository;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAllInBatch();
    }

    @Test
    void shouldReturnUnauthorizedWithoutToken() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isUnauthorized());

        assertThat(repository.count()).isZero();
    }

    @Test
    void shouldRejectCreationWithoutWriteScope() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .with(consumer("consumer-a", "requests:read"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isForbidden());

        assertThat(repository.count()).isZero();
    }

    @Test
    void shouldCreateRequestAndReturnLocation() throws Exception {
        var result = mockMvc.perform(post("/api/v1/requests")
                        .with(consumer("consumer-a", "requests:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.externalId").value("EXT-12345"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        assertThat(location).isNotBlank();

        mockMvc.perform(get(location)
                        .with(consumer("consumer-a", "requests:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalId").value("EXT-12345"));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void shouldReturnExistingRequestForIdenticalRetry() throws Exception {
        ActivationRequest existing = saveRequest();

        mockMvc.perform(post("/api/v1/requests")
                        .with(consumer("consumer-a", "requests:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id")
                        .value(existing.getId().toString()));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void shouldRejectConflictingRequest() throws Exception {
        saveRequest();

        mockMvc.perform(post("/api/v1/requests")
                        .with(consumer("consumer-a", "requests:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY.replace(
                                "INTERNET-500", "INTERNET-1000"
                        )))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON
                ))
                .andExpect(jsonPath("$.status").value(409));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void shouldRejectInvalidRequestBody() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .with(consumer("consumer-a", "requests:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "externalId": "",
                                  "customerId": "CUST-10001",
                                  "productCode": "INTERNET-500"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON
                ))
                .andExpect(jsonPath("$.errors[0].field")
                        .value("externalId"));

        assertThat(repository.count()).isZero();
    }

    @Test
    void shouldReturnNotFoundForUnknownRequest() throws Exception {
        mockMvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID())
                        .with(consumer("consumer-a", "requests:read")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void shouldHideRequestFromAnotherConsumer() throws Exception {
        ActivationRequest existing = saveRequest();

        mockMvc.perform(get("/api/v1/requests/{id}", existing.getId())
                        .with(consumer("consumer-b", "requests:read")))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldRejectLookupWithoutReadScope() throws Exception {
        ActivationRequest existing = saveRequest();

        mockMvc.perform(get("/api/v1/requests/{id}", existing.getId())
                        .with(consumer("consumer-a", "requests:write")))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectInvalidRequestId() throws Exception {
        mockMvc.perform(get("/api/v1/requests/invalid-id")
                        .with(consumer("consumer-a", "requests:read")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void shouldRejectMalformedJson() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .with(consumer("consumer-a", "requests:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON
                ))
                .andExpect(jsonPath("$.status").value(400));

        assertThat(repository.count()).isZero();
    }

    @Test
    void shouldRejectExternalIdOwnedByAnotherConsumer() throws Exception {
        ActivationRequest existing = saveRequest();

        mockMvc.perform(post("/api/v1/requests")
                        .with(consumer("consumer-b", "requests:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.customerId").doesNotExist());

        assertThat(repository.count()).isEqualTo(1);

        ActivationRequest persisted = repository.findById(existing.getId())
                .orElseThrow();

        assertThat(persisted.getConsumerId()).isEqualTo("consumer-a");
    }

    private ActivationRequest saveRequest() {
        return repository.saveAndFlush(ActivationRequest.create(
                "EXT-12345",
                "CUST-10001",
                "INTERNET-500",
                "consumer-a",
                Instant.now()
        ));
    }

    private RequestPostProcessor consumer(String subject, String scope) {
        return jwt()
                .jwt(token -> token.subject(subject))
                .authorities(new SimpleGrantedAuthority("SCOPE_" + scope));
    }
}