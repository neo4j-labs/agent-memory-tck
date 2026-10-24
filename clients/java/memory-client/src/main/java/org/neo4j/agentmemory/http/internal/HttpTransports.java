package org.neo4j.agentmemory.http.internal;

import org.neo4j.agentmemory.http.HttpTransport;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.concurrent.Executor;

public final class HttpTransports {

    public static HttpTransport select(dev.langchain4j.http.client.HttpClient httpClient, Executor executor) {
        return new LangChain4jHttpTransport(httpClient, executor);
    }

    public static HttpTransport select(RestClient restClient, Executor executor) {
        return new RestClientHttpTransport(restClient, executor);
    }

    public static HttpTransport select(WebClient webClient) {
        return new WebClientHttpTransport(webClient);
    }

    public static HttpTransport select(java.net.http.HttpClient httpClient) {
        return new JdkHttpTransport(httpClient);
    }
}
