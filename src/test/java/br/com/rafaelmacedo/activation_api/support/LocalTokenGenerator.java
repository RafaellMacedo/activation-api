package br.com.rafaelmacedo.activation_api.support;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

public class LocalTokenGenerator {

    public static void main(String[] args) throws Exception {
        RSAKey key = new RSAKeyGenerator(2048)
                .keyID(UUID.randomUUID().toString())
                .generate();

        Instant now = Instant.now();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("http://local-auth")
                .subject("local-consumer")
                .audience("activation-api")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .claim("scope", "requests:read requests:write")
                .build();

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(key.getKeyID())
                .build();

        SignedJWT token = new SignedJWT(header, claims);
        token.sign(new RSASSASigner(key));

        Path output = Path.of("target", "local-auth");
        Path publicDirectory = output.resolve("public");

        Files.createDirectories(publicDirectory);

        Files.writeString(
                publicDirectory.resolve("jwks.json"),
                new JWKSet(key.toPublicJWK()).toString(),
                StandardCharsets.UTF_8
        );

        Files.writeString(
                output.resolve("access-token.txt"),
                token.serialize(),
                StandardCharsets.UTF_8
        );

        System.out.println(
                "Local token generated at target/local-auth/access-token.txt"
        );
        System.out.println("Token expires at " + claims.getExpirationTime());
    }
}