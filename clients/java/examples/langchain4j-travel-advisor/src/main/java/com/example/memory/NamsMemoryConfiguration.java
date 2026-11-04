package com.example.memory;

import io.micronaut.context.annotation.ConfigurationProperties;
import java.time.Duration;

/** Runtime settings for the workspace-bound NAMS client. */
@ConfigurationProperties("nams")
public class NamsMemoryConfiguration {
    private String baseUrl;
    private String apiKey;
    private Duration waitTimeout = Duration.ofSeconds(30);

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public Duration getWaitTimeout() { return waitTimeout; }
    public void setWaitTimeout(Duration waitTimeout) { this.waitTimeout = waitTimeout; }
}
