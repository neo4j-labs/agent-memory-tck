package org.neo4j.agentmemory;

import org.neo4j.agentmemory.http.HttpTransport;
import org.neo4j.agentmemory.http.internal.HttpTransports;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Immutable settings for constructing a hosted memory client.
 * Build with {@link #builder()}; environment settings are resolved and validated
 * by {@link Builder#build()}. Authentication requires a Workspace API key;
 * Admin keys with an explicit workspace ID are not yet supported.
 */
public final class MemoryClientConfiguration {
    private static final String DEFAULT_BASE_URL = "https://memory.neo4jlabs.com/v1";
    private final URI baseUrl;
    private final String apiKey;
    private final HttpTransport httpTransport;

    private MemoryClientConfiguration(URI baseUrl, String apiKey, HttpTransport httpTransport) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.httpTransport = httpTransport;
    }

    /** Returns the service base URL, including its version path. */
    public URI baseUrl() {
        return baseUrl;
    }

    /** Returns the Workspace API key used for hosted operations. */
    public String apiKey() {
        return apiKey;
    }

    /**
     * Returns the HTTP transport that carries memory requests: the one built
     * from the configured HTTP client, a custom transport, or the default JDK
     * transport.
     */
    public HttpTransport httpTransport() {
        return httpTransport;
    }

    /** Returns a new builder with no explicit overrides. */
    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for client settings. */
    public static final class Builder {
        private String baseUrl;
        private String apiKey;
        private HttpTransport httpTransport;

        private Builder() {}

        /**
         * Overrides MEMORY_ENDPOINT with a service URL including its version path.
         * Leading and trailing whitespace is trimmed when building; a trailing
         * slash is supported. Null is rejected immediately; blank values fail in build().
         *
         * @param baseUrl an absolute HTTP(S) URL with a host and no user info,
         *        query, or fragment
         * @return this builder
         * @throws NullPointerException if baseUrl is null
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
            return this;
        }

        /**
         * Overrides MEMORY_API_KEY with a Workspace API key.
         * Null is rejected immediately; blank values fail in build().
         *
         * @param apiKey the nonblank Workspace API key
         * @return this builder
         * @throws NullPointerException if apiKey is null
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
            return this;
        }

        /**
         * Sends memory requests through a custom transport, for an HTTP client
         * without a built-in setter. The transport must follow the
         * {@link HttpTransport} contract and is never closed.
         * Replaces any HTTP client set earlier.
         *
         * @param httpTransport the application transport
         * @return this builder
         * @throws NullPointerException if httpTransport is null
         */
        public Builder httpTransport(HttpTransport httpTransport) {
            this.httpTransport = Objects.requireNonNull(httpTransport, "httpTransport");
            return this;
        }

        /**
         * Uses the supplied HTTP client as configured, including its executor,
         * proxy, timeouts, and TLS settings. The client is never closed.
         * Replaces any HTTP client set earlier.
         *
         * @param httpClient the application HTTP client
         * @return this builder
         * @throws NullPointerException if httpClient is null
         */
        public Builder jdkHttpClient(HttpClient httpClient) {
            this.httpTransport = HttpTransports.select(httpClient);
            return this;
        }

        /**
         * Uses an application-built Spring client. Its base URL and status
         * handlers do not apply to memory requests; everything else configured on
         * it does, including default headers, cookies, interceptors, observations,
         * timeouts, and TLS settings. Memory headers replace same-named headers.
         * Blocking exchanges run on executor; neither the client nor the executor
         * is closed. Requires Spring Framework 7.0 or later.
         * Replaces any HTTP client set earlier.
         *
         * @param restClient the application Spring client
         * @param executor the application executor for blocking exchanges
         * @return this builder
         * @throws NullPointerException if restClient or executor is null
         */
        public Builder restClient(RestClient restClient, Executor executor) {
            this.httpTransport = HttpTransports.select(restClient, executor);
            return this;
        }

        /**
         * Uses an application-built Spring reactive client without blocking. Its
         * base URL and status handlers do not apply to memory requests; everything
         * else configured on it does, including default headers, cookies, filters,
         * observations, and its in-memory buffer limit, which bounds response
         * size. Memory headers replace same-named headers. The client is never
         * closed. Requires Spring Framework 7.0 or later.
         * Replaces any HTTP client set earlier.
         *
         * @param webClient the application Spring reactive client
         * @return this builder
         * @throws NullPointerException if webClient is null
         */
        public Builder webClient(WebClient webClient) {
            this.httpTransport = HttpTransports.select(webClient);
            return this;
        }

        /**
         * Uses the supplied LangChain4j client as configured. Non-success responses
         * lose their headers and content type. Blocking exchanges run on executor;
         * neither the client nor the executor is closed. Requires LangChain4j 1.0
         * or later. Replaces any HTTP client set earlier.
         *
         * @param httpClient the application LangChain4j HTTP client
         * @param executor the application executor for blocking exchanges
         * @return this builder
         * @throws NullPointerException if httpClient or executor is null
         */
        public Builder langChain4jHttpClient(dev.langchain4j.http.client.HttpClient httpClient, Executor executor) {
            this.httpTransport = HttpTransports.select(httpClient, executor);
            return this;
        }

        /**
         * Resolves omitted settings from MEMORY_API_KEY and MEMORY_ENDPOINT and
         * validates them without creating a client or making a remote request.
         * An absent or blank environment base URL defaults to
         * https://memory.neo4jlabs.com/v1 and emits a WARNING through System.Logger.
         * The key is required.
         *
         * @return a new immutable configuration, independent of later builder changes
         * @throws IllegalArgumentException if the key is null or blank, or the
         *         base URL is not an absolute HTTP(S) URL with a host and no
         *         user info, query, or fragment
         */
        public MemoryClientConfiguration build() {
            var resolvedKey = apiKey != null ? apiKey : System.getenv("MEMORY_API_KEY");
            var resolvedUrl = baseUrl != null ? baseUrl : System.getenv("MEMORY_ENDPOINT");
            if (resolvedKey == null || resolvedKey.isBlank()) {
                throw new IllegalArgumentException("apiKey must not be blank");
            }
            if (baseUrl == null && (resolvedUrl == null || resolvedUrl.isBlank())) {
                resolvedUrl = DEFAULT_BASE_URL;
                logDefaultBaseUrl();
            }
            URI url;
            try {
                url = URI.create(resolvedUrl.trim());
            } catch (IllegalArgumentException invalidUrl) {
                throw invalidBaseUrl();
            }
            if (url.getHost() == null
                    || !("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))
                    || url.getRawUserInfo() != null || url.getRawQuery() != null || url.getRawFragment() != null) {
                throw invalidBaseUrl();
            }

            var resolvedHttpTransport = httpTransport != null? httpTransport : HttpTransports.select(HttpClient.newHttpClient());
            return new MemoryClientConfiguration(url, resolvedKey, resolvedHttpTransport);
        }

        private static IllegalArgumentException invalidBaseUrl() {
            return new IllegalArgumentException(
                    "baseUrl must be an absolute HTTP(S) URL without user info, query, or fragment");
        }

        private static void logDefaultBaseUrl() {
            try {
                System.getLogger(MemoryClientConfiguration.class.getName()).log(System.Logger.Level.WARNING,
                        "MEMORY_ENDPOINT is absent or blank; using default base URL " + DEFAULT_BASE_URL);
            } catch (RuntimeException ignored) {
                // Logging has no effect on configuration validation.
            }
        }
    }
}
