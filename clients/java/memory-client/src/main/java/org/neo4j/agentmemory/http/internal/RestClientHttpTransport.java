package org.neo4j.agentmemory.http.internal;

import org.neo4j.agentmemory.http.HttpCall;
import org.neo4j.agentmemory.http.HttpResult;
import org.neo4j.agentmemory.http.HttpTransport;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Sends blocking exchanges through an application-owned Spring client. The
 * client's own defaults, such as headers and cookies, still apply; memory
 * headers replace same-named ones.
 */
final class RestClientHttpTransport implements HttpTransport {
    private final RestClient restClient;
    private final Executor executor;

    public RestClientHttpTransport(RestClient restClient, Executor executor) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public String name() {
        return "spring-rest-client";
    }

    @Override
    public CompletableFuture<HttpResult> send(HttpCall call) {
        return CompletableFuture.supplyAsync(() -> {
            var uriSpec = restClient.method(HttpMethod.valueOf(call.method()));
            var request = call.uriVariables().values().stream().anyMatch(String.class::isInstance)
                    ? uriSpec.uri(call.uriTemplate(), builder -> call.uri())
                    : uriSpec.uri(call.uriTemplate(), call.uriVariables());
            request.headers(headers -> call.headers().forEach(headers::set));
            if (!(call.body() instanceof HttpCall.NoBody)) {
                request.body(call.body().body());
            }
            return request.exchange((sentRequest, response) -> {
                var headers = new LinkedHashMap<String, List<String>>();
                response.getHeaders().forEach(headers::put);
                return new HttpResult(response.getStatusCode().value(), headers,
                        response.getBody().readAllBytes());
            });
        }, executor);
    }
}
