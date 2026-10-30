package br.com.rafaelmacedo.activation_api;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JwtValidationTest {

    private static final String ISSUER = "https://issuer.test";
    private static final String AUDIENCE = "activation-api";
    private static final String KEY_ID = "test-key";

    private static final RSAKey SIGNING_KEY = generateKey();
    private static final RSAKey UNTRUSTED_KEY = generateKey();
    private static final HttpServer JWKS_SERVER = startJwksServer();

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) {
        registry.add("app.security.issuer", () -> ISSUER);
        registry.add("app.security.audience", () -> AUDIENCE);
        registry.add(
                "app.security.jwk-set-uri",
                () -> "http://127.0.0.1:"
                        + JWKS_SERVER.getAddress().getPort()
                        + "/jwks"
        );
    }

    @AfterAll
    static void stopJwksServer() {
        JWKS_SERVER.stop(0);
    }

    @Test
    void shouldAcceptValidSignedToken() throws Exception {
        String token = sign(validClaims().build(), SIGNING_KEY);

        mockMvc.perform(get(requestPath())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldRejectInvalidSignature() throws Exception {
        String token = sign(validClaims().build(), UNTRUSTED_KEY);

        mockMvc.perform(get(requestPath())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectExpiredToken() throws Exception {
        Instant now = Instant.now();

        String token = sign(
                validClaims()
                        .issueTime(Date.from(now.minusSeconds(600)))
                        .expirationTime(Date.from(now.minusSeconds(300)))
                        .build(),
                SIGNING_KEY
        );

        mockMvc.perform(get(requestPath())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectUnexpectedIssuer() throws Exception {
        String token = sign(
                validClaims()
                        .issuer("https://untrusted-issuer.test")
                        .build(),
                SIGNING_KEY
        );

        mockMvc.perform(get(requestPath())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectUnexpectedAudience() throws Exception {
        String token = sign(
                validClaims()
                        .audience("another-api")
                        .build(),
                SIGNING_KEY
        );

        mockMvc.perform(get(requestPath())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectMissingSubject() throws Exception {
        String token = sign(
                validClaims()
                        .subject(null)
                        .build(),
                SIGNING_KEY
        );

        mockMvc.perform(get(requestPath())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectMissingExpiration() throws Exception {
        String token = sign(
                validClaims()
                        .expirationTime(null)
                        .build(),
                SIGNING_KEY
        );

        mockMvc.perform(get(requestPath())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectValidTokenWithoutReadScope() throws Exception {
        String token = sign(
                validClaims()
                        .claim("scope", "requests:write")
                        .build(),
                SIGNING_KEY
        );

        mockMvc.perform(get(requestPath())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectBlankSubject() throws Exception {
        String token = sign(
                validClaims().subject(" ").build(),
                SIGNING_KEY
        );

        mockMvc.perform(get(requestPath())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectSubjectExceedingMaximumLength() throws Exception {
        String token = sign(
                validClaims().subject("A".repeat(101)).build(),
                SIGNING_KEY
        );

        mockMvc.perform(get(requestPath())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectTokenBeforeNotBeforeTime() throws Exception {
        Instant now = Instant.now();

        String token = sign(
                validClaims()
                        .notBeforeTime(Date.from(now.plusSeconds(300)))
                        .expirationTime(Date.from(now.plusSeconds(600)))
                        .build(),
                SIGNING_KEY
        );

        mockMvc.perform(get(requestPath())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    private JWTClaimsSet.Builder validClaims() {
        Instant now = Instant.now();

        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject("consumer-a")
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("scope", "requests:read");
    }

    private String sign(JWTClaimsSet claims, RSAKey key) throws Exception {
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(KEY_ID)
                .build();

        SignedJWT token = new SignedJWT(header, claims);
        token.sign(new RSASSASigner(key));

        return token.serialize();
    }

    private String requestPath() {
        return "/api/v1/requests/" + UUID.randomUUID();
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048)
                    .keyID(KEY_ID)
                    .generate();
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not generate test signing key",
                    exception
            );
        }
    }

    private static HttpServer startJwksServer() {
        try {
            byte[] response = new JWKSet(SIGNING_KEY.toPublicJWK())
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);

            HttpServer server = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0),
                    0
            );

            server.createContext("/jwks", exchange -> {
                try {
                    exchange.getResponseHeaders().set(
                            "Content-Type",
                            "application/json"
                    );

                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                } finally {
                    exchange.close();
                }
            });

            server.start();
            return server;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not start test JWKS server",
                    exception
            );
        }
    }
}