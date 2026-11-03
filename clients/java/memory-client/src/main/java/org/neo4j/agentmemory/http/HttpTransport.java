package org.neo4j.agentmemory.http;

import java.util.concurrent.CompletableFuture;

/**
 * Sends memory requests through an HTTP client. Implement it to use a client
 * without a built-in setter, and supply it through
 * {@code MemoryClientConfiguration.Builder.httpTransport(...)}. The memory client
 * owns routes, authentication, JSON, and failure mapping; a transport only
 * exchanges bytes. An implementation must:
 * <ul>
 *   <li>return from {@link #send} without waiting on I/O, running a blocking
 *       client's exchange on an application executor;</li>
 *   <li>complete with an {@link HttpResult} for every HTTP response, including
 *       4xx and 5xx;</li>
 *   <li>send to {@link HttpCall#uri()} with {@link HttpCall#headers()}, which take
 *       precedence over same-named headers the wrapped client would add;</li>
 *   <li>send {@link HttpCall.NoBody} as no request body, and the bytes of any
 *       other body unchanged;</li>
 *   <li>perform no retries and never close the wrapped client or executor;</li>
 *   <li>treat cancellation as best effort.</li>
 * </ul>
 * Failures need no translation: an exception thrown by {@code send} or a failed
 * future becomes a {@code MemoryClientException} that retains the cause.
 */
public interface HttpTransport {
    /**
     * Returns a short, nonblank, kebab-case name reported as {@code transport=}
     * when a client is initialized. Built-in names are {@code jdk-http},
     * {@code spring-rest-client}, {@code spring-web-client}, and
     * {@code langchain4j-http}.
     */
    String name();

    /** Starts one exchange and returns its uninterpreted response. */
    CompletableFuture<HttpResult> send(HttpCall call);
}
