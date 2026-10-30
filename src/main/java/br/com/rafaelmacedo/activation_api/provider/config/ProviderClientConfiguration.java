package br.com.rafaelmacedo.activation_api.provider.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProviderProperties.class)
public class ProviderClientConfiguration {

    @Bean(destroyMethod = "close")
    public HttpClient providerHttpClient(ProviderProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    public RestClient providerRestClient(
            ProviderProperties properties,
            @Qualifier("providerHttpClient") HttpClient httpClient
    ) {
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeader(
                        "Accept",
                        MediaType.APPLICATION_JSON_VALUE
                );

        if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
            builder.defaultHeader("X-API-Key", properties.apiKey());
        }

        return builder.build();
    }
}