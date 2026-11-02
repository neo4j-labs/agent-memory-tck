package com.example.memory;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the native memory client; timeout bounds each required native wait
 * and each background native stage completion, including conversation lookup. */
@ConfigurationProperties("app.memory")
public record NamsProperties(URI endpoint, String apiKey, Duration timeout) {
    public NamsProperties {
        if (endpoint == null || !endpoint.isAbsolute() || endpoint.getHost() == null
                || !("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null || endpoint.getRawUserInfo() != null
                || !("/v1".equals(endpoint.getRawPath()) || "/v1/".equals(endpoint.getRawPath()))) {
            throw new IllegalArgumentException("Memory endpoint must be an HTTP(S) URI with /v1 path and no credentials, query or fragment");
        }
        if (endpoint.getRawPath().endsWith("/")) endpoint = URI.create(endpoint.toString().substring(0, endpoint.toString().length() - 1));
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("Memory API key must not be blank");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Memory timeout must be positive");
        try { timeout.toNanos(); }
        catch (ArithmeticException failure) { throw new IllegalArgumentException("Memory timeout must be representable in nanoseconds"); }
    }
}
