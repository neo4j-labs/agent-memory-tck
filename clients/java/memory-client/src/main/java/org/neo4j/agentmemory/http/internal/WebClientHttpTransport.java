package org.neo4j.agentmemory.http.internal;

import org.neo4j.agentmemory.http.HttpCall;
import org.neo4j.agentmemory.http.HttpResult;
import org.neo4j.agentmemory.http.HttpTransport;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Sends non-blocking exchanges through an application-owned Spring client.
 * Response bodies are buffered through the client's codecs, so its in-memory
 * buffer limit applies.
 */
final class WebClientHttpTransport implements HttpTransport {
    private final WebClient webClient;

    public WebClientHttpTransport(WebClient webClient) {
        this.webClient = Objects.requireNonNull(webClient, "webClient");
    }

    @Override
    public String name() {
        return "spring-web-client";
    }

    @Override
    public CompletableFuture<HttpResult> send(HttpCall call) {
        var uriSpec = webClient.method(HttpMethod.valueOf(call.method()));
        var request = call.uriVariables().values().stream().anyMatch(String.class::isInstance)
                ? uriSpec.uri(call.uriTemplate(), builder -> call.uri())
                : uriSpec.uri(call.uriTemplate(), call.uriVariables());
        request.headers(headers -> call.headers().forEach(headers::set));
        WebClient.RequestHeadersSpec<?> exchange = call.body() instanceof HttpCall.NoBody
                ? request : request.bodyValue(call.body().body());
        return exchange.exchangeToMono(response -> response.bodyToMono(byte[].class)
                .defaultIfEmpty(new byte[0])
                .map(body -> {
                    var headers = new LinkedHashMap<String, List<String>>();
                    response.headers().asHttpHeaders().forEach(headers::put);
                    return new HttpResult(response.statusCode().value(), headers, body);
                }))
                .toFuture();
    }
}
