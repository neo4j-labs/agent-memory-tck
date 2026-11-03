package org.neo4j.agentmemory.http.internal;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import org.neo4j.agentmemory.http.HttpCall;
import org.neo4j.agentmemory.http.HttpResult;
import org.neo4j.agentmemory.http.HttpTransport;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Sends blocking exchanges through an application-owned LangChain4j client. */
final class LangChain4jHttpTransport implements HttpTransport {
    private final HttpClient httpClient;
    private final Executor executor;

    public LangChain4jHttpTransport(HttpClient httpClient, Executor executor) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public String name() {
        return "langchain4j-http";
    }

    @Override
    public CompletableFuture<HttpResult> send(HttpCall call) {
        return CompletableFuture.supplyAsync(() -> {
            var request = HttpRequest.builder().method(HttpMethod.valueOf(call.method()))
                    .url(call.uri().toString()).addHeaders(call.headers());
            if (!(call.body() instanceof HttpCall.NoBody)) {
                request.body(new String(call.body().body(), UTF_8));
            }
            try {
                var response = httpClient.execute(request.build());
                return new HttpResult(response.statusCode(), response.headers(),
                        Objects.toString(response.body(), "").getBytes(UTF_8));
            } catch (HttpException failure) {
                // LangChain4j discards non-success response headers, including content type.
                return new HttpResult(failure.statusCode(), Map.of(),
                        Objects.toString(failure.getMessage(), "").getBytes(UTF_8));
            }
        }, executor);
    }
}
