package org.neo4j.agentmemory.http.integrations;

import org.neo4j.agentmemory.MemoryClientConfiguration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Configures the clients exercised by the shared HTTP suites. */
enum HttpClientUnderTest {
    JDK_DEFAULT,
    JDK_CONFIGURED,
    REST_CLIENT,
    WEB_CLIENT,
    LANGCHAIN4J;

    public static final Executor TEST_EXECUTOR = Executors.newCachedThreadPool(command -> {
        var thread = new Thread(command, "memory-client-test");
        thread.setDaemon(true);
        return thread;
    });

    public MemoryClientConfiguration.Builder configure(MemoryClientConfiguration.Builder builder) {
        return configure(builder, TEST_EXECUTOR);
    }

    public MemoryClientConfiguration.Builder configure(
            MemoryClientConfiguration.Builder builder, Executor executor) {
        return switch (this) {
            case JDK_DEFAULT -> builder;
            case JDK_CONFIGURED -> builder.jdkHttpClient(HttpClient.newHttpClient());
            case REST_CLIENT -> builder.restClient(RestClient.builder()
                    .requestFactory(new JdkClientHttpRequestFactory()).build(), executor);
            case WEB_CLIENT -> builder.webClient(WebClient.create());
            case LANGCHAIN4J -> builder.langChain4jHttpClient(
                    dev.langchain4j.http.client.jdk.JdkHttpClient.builder().build(), executor);
        };
    }

    public boolean takesExecutor() {
        return this == REST_CLIENT || this == LANGCHAIN4J;
    }

    public boolean keepsFailureHeaders() {
        return this != LANGCHAIN4J;
    }
}
