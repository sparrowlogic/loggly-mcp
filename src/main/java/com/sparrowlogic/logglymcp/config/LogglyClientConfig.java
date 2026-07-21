package com.sparrowlogic.logglymcp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Wires the {@link RestClient} used to talk to the Loggly event-retrieval API, pre-configured
 * with the per-account base URL and the bearer auth header every request requires.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LogglyProperties.class)
public class LogglyClientConfig {

	@Bean
	RestClient logglyRestClient(LogglyProperties properties) {
		// Use RestClient.builder() directly rather than the autoconfigured
		// RestClient.Builder: this app runs with web-application-type=none, so the
		// web client autoconfiguration is not active. The default builder still
		// registers the standard message converters (including Jackson).
		return RestClient.builder()
				// https://<subdomain>.loggly.com/apiv2 — derived from the subdomain property.
				.baseUrl(properties.baseUrl())
				// Loggly API tokens are sent as "bearer <value>" (lowercase, per the docs).
				.defaultHeader(HttpHeaders.AUTHORIZATION, "bearer " + properties.apiToken())
				.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
				.build();
	}
}
