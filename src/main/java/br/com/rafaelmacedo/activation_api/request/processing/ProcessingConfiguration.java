package br.com.rafaelmacedo.activation_api.request.processing;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProcessingProperties.class)
@EnableScheduling
public class ProcessingConfiguration {
}