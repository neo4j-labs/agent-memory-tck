package org.neo4j.agentmemory.http.internal;

import org.neo4j.agentmemory.http.HttpCall;
import org.neo4j.agentmemory.http.HttpResult;
import org.neo4j.agentmemory.http.HttpTransport;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Sends exchanges through an application-owned JDK HTTP client. */
final class JdkHttpTransport implements HttpTransport {
    private final HttpClient httpClient;

    public JdkHttpTransport(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public String name() { return "jdk-http"; }

    @Override
    public CompletableFuture<HttpResult> send(HttpCall call) {
        var publisher = call.body() instanceof HttpCall.NoBody ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(call.body().body());
        var request = HttpRequest.newBuilder(call.uri()).method(call.method(), publisher);
        call.headers().forEach(request::header);
        return httpClient.sendAsync(request.build(), HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> new HttpResult(response.statusCode(), response.headers().map(), response.body()));
    }
}
