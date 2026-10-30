package br.com.rafaelmacedo.activation_api.provider.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "app.provider")
public record ProviderProperties(
        URI baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        String apiKey
) {

    public ProviderProperties {
        if (baseUrl == null
                || baseUrl.getHost() == null
                || (!"http".equalsIgnoreCase(baseUrl.getScheme())
                && !"https".equalsIgnoreCase(baseUrl.getScheme()))) {
            throw new IllegalArgumentException(
                    "Provider base URL must be an absolute HTTP or HTTPS URL"
            );
        }

        if (baseUrl.getUserInfo() != null) {
            throw new IllegalArgumentException(
                    "Provider base URL must not contain credentials"
            );
        }

        requirePositive(connectTimeout, "Provider connection timeout");
        requirePositive(readTimeout, "Provider read timeout");
    }

    private static void requirePositive(Duration duration, String field) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}