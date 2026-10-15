package org.neo4j.agentmemory;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.Test;
import org.neo4j.agentmemory.conversation.ListConversations;
import org.neo4j.agentmemory.testsupport.EnvironmentProbe;
import org.neo4j.agentmemory.testsupport.OpenApiContract;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.neo4j.agentmemory.testsupport.TestAwaitSupport.await;

@WireMockTest
class MemoryClientFactoryTest {
    @Test
    void defaultClientRunsWithoutSpringOrLangChain4j(WireMockRuntimeInfo server) throws Exception {
        var response = OpenApiContract.response("get", "/v1/conversations/{id}");
        var id = UUID.fromString(tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(response.build().getBody()).get("id").asString());
        stubFor(get(urlEqualTo("/v1/conversations/" + id)).willReturn(response));

        var output = EnvironmentProbe.runOnClasspath(
                EnvironmentProbe.classpathWithout(
                        "spring-", "langchain4j-", "micrometer-", "reactor-", "reactive-streams-", "netty-"),
                FrameworkFreeProbe.class,
                Map.of("NAMS_API_KEY", "workspace-key", "NAMS_BASE_URL", server.getHttpBaseUrl() + "/v1"),
                id.toString());

        assertThat(output.lines()).contains("spring=absent", "langchain4j=absent",
                "conversation=" + id, "builderReflection=NoClassDefFoundError");
        verify(getRequestedFor(urlEqualTo("/v1/conversations/" + id))
                .withHeader("Authorization", equalTo("Bearer workspace-key")));
        OpenApiContract.assertEveryExchangeMatchesTheContract();
        OpenApiContract.assertOnlyDeclaredRequestProperties();
    }

    public static class FrameworkFreeProbe {
        public static void main(String[] args) throws Exception {
            try {
                Class.forName("org.springframework.web.client.RestClient");
            } catch (ClassNotFoundException absent) {
                System.out.println("spring=absent");
            }
            try {
                Class.forName("dev.langchain4j.http.client.HttpClient");
            } catch (ClassNotFoundException absent) {
                System.out.println("langchain4j=absent");
            }
            System.out.println("conversation=" + await(MemoryClient.create()
                    .getConversation(UUID.fromString(args[0]))).id());
            try {
                MemoryClientConfiguration.Builder.class.getDeclaredMethods();
            } catch (NoClassDefFoundError absent) {
                System.out.println("builderReflection=NoClassDefFoundError");
            }
        }
    }

    @Test
    void usesTheConfiguredJdkHttpClient(WireMockRuntimeInfo server) {
        var executions = new AtomicInteger();
        var pool = Executors.newCachedThreadPool();
        Executor counting = command -> {
            executions.incrementAndGet();
            pool.execute(command);
        };
        try {
            var id = UUID.randomUUID();
            stubFor(get(urlEqualTo("/v1/conversations/" + id))
                    .willReturn(OpenApiContract.response("get", "/v1/conversations/{id}")));
            var client = MemoryClient.create(MemoryClientConfiguration.builder()
                    .baseUrl(server.getHttpBaseUrl() + "/v1").apiKey("workspace-key")
                    .jdkHttpClient(HttpClient.newBuilder().executor(counting).build()).build());

            assertThat(await(client.getConversation(id)).id()).isNotNull();
            assertThat(executions.get()).isGreaterThan(0);
            OpenApiContract.assertEveryExchangeMatchesTheContract();
            OpenApiContract.assertOnlyDeclaredRequestProperties();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void environmentFactoryUsesConfiguredUrlAndWorkspaceApiKey(WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlEqualTo("/v1/conversations?limit=10"))
                .willReturn(OpenApiContract.response("get", "/v1/conversations")));

        assertThat(probe(Map.of("NAMS_BASE_URL", "  " + server.getHttpBaseUrl() + "/v1  ",
                "NAMS_API_KEY", "workspace-key"), "read")).contains("read-success");

        verify(getRequestedFor(urlEqualTo("/v1/conversations?limit=10"))
                .withHeader("Authorization", equalTo("Bearer workspace-key")));
        OpenApiContract.assertEveryExchangeMatchesTheContract();
        OpenApiContract.assertOnlyDeclaredRequestProperties();
    }

    @Test
    void environmentFactoryRejectsAbsentOrBlankApiKey() throws Exception {
        for (var environment : java.util.List.of(Map.<String, String>of(),
                Map.of("NAMS_API_KEY", " \t"))) {
            assertThat(probe(environment, "create")).contains("apiKey must not be blank");
        }
    }

    @Test
    void factoriesUseDefaultUrlForAbsentOrBlankConfiguration() throws Exception {
        assertThat(probe(Map.of("NAMS_API_KEY", "workspace-key"), "create"))
                .contains("constructed", "event=client.initialized transport=jdk-http json=jackson3 baseUrl=https://memory.neo4jlabs.com/v1")
                .doesNotContain("workspace-key");
        assertThat(probe(Map.of("NAMS_API_KEY", "workspace-key", "NAMS_BASE_URL", " \t"),
                "create")).contains("constructed");
    }

    @Test
    void configurationKeyOverridesEnvironmentAndUsesConfiguredUrl(WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlEqualTo("/v1/conversations?limit=10"))
                .willReturn(OpenApiContract.response("get", "/v1/conversations")));

        assertThat(probe(Map.of("NAMS_BASE_URL", server.getHttpBaseUrl() + "/v1",
                "NAMS_API_KEY", "unused-environment-key"), "explicit-key")).contains("read-success");

        verify(getRequestedFor(urlEqualTo("/v1/conversations?limit=10"))
                .withHeader("Authorization", equalTo("Bearer explicit-key")));
        OpenApiContract.assertEveryExchangeMatchesTheContract();
        OpenApiContract.assertOnlyDeclaredRequestProperties();
    }

    @Test
    void configurationOverridesEnvironmentAndSupportsTrailingSlash(WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlEqualTo("/v1/conversations?limit=10"))
                .willReturn(OpenApiContract.response("get", "/v1/conversations")));

        assertThat(EnvironmentProbe.run(FactoryProbe.class,
                Map.of("NAMS_BASE_URL", "invalid environment URL", "NAMS_API_KEY", "unused-key"),
                "explicit-configuration", server.getHttpBaseUrl() + "/v1/"))
                .contains("read-success", "baseUrl=" + server.getHttpBaseUrl() + "/v1/")
                .doesNotContain("unused-key", "explicit-key", "invalid environment URL");

        verify(getRequestedFor(urlEqualTo("/v1/conversations?limit=10"))
                .withHeader("Authorization", equalTo("Bearer explicit-key")));
        OpenApiContract.assertEveryExchangeMatchesTheContract();
        OpenApiContract.assertOnlyDeclaredRequestProperties();
    }

    @Test
    void createsClientFromBuiltConfiguration() {
        assertThat(MemoryClient.create(MemoryClientConfiguration.builder()
                .baseUrl("https://memory.test/v1").apiKey("key").build()))
                .isNotNull();
    }

    @Test
    void rejectsNullConfiguration() {
        assertThatThrownBy(() -> MemoryClient.create(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("configuration");
    }

    private static String probe(Map<String, String> environment, String mode) throws Exception {
        return EnvironmentProbe.run(FactoryProbe.class, environment, mode);
    }

    public static class FactoryProbe {
        public static void main(String[] args) throws Exception {
            try {
                MemoryClient client;
                if (args[0].equals("explicit-configuration")) {
                    client = MemoryClient.create(MemoryClientConfiguration.builder()
                            .apiKey("explicit-key").baseUrl(args[1]).build());
                } else if (args[0].equals("explicit-key")) {
                    client = MemoryClient.create(MemoryClientConfiguration.builder().apiKey("explicit-key").build());
                } else {
                    client = MemoryClient.create();
                }
                if (args[0].equals("create")) {
                    System.out.println("constructed");
                } else {
                    client.listConversations(new ListConversations(10)).get(5, TimeUnit.SECONDS);
                    System.out.println("read-success");
                }
            } catch (IllegalArgumentException failure) {
                System.out.println(failure.getMessage());
            }
        }
    }
}
