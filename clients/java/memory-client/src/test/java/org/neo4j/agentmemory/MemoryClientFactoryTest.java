package org.neo4j.agentmemory;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.github.netmikey.logunit.api.LogCapturer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junitpioneer.jupiter.ClearEnvironmentVariable;
import org.junitpioneer.jupiter.SetEnvironmentVariable;
import org.neo4j.agentmemory.conversation.ListConversations;
import org.neo4j.agentmemory.testsupport.OpenApiContract;
import org.slf4j.event.LoggingEvent;

import java.net.http.HttpClient;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.neo4j.agentmemory.testsupport.TestAwaitSupport.await;

@WireMockTest
@ClearEnvironmentVariable(key = "NAMS_API_KEY")
@ClearEnvironmentVariable(key = "NAMS_BASE_URL")
class MemoryClientFactoryTest {

    @RegisterExtension
    final LogCapturer logs = LogCapturer.create().captureForType(MemoryClient.class);

    @Test
    @Tag("without-frameworks")
    void defaultClientRunsWithoutSpringOrLangChain4j(WireMockRuntimeInfo server) {
        assertThatThrownBy(() -> Class.forName("org.springframework.web.client.RestClient"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("dev.langchain4j.http.client.HttpClient"))
                .isInstanceOf(ClassNotFoundException.class);

        var id = UUID.randomUUID();
        stubFor(get(urlEqualTo("/v1/conversations/" + id))
                .willReturn(OpenApiContract.response("get", "/v1/conversations/{id}")));
        var client = MemoryClient.create(MemoryClientConfiguration.builder()
                .apiKey("workspace-key").baseUrl(server.getHttpBaseUrl() + "/v1").build());

        assertThat(await(client.getConversation(id)).id()).isNotNull();
        logs.assertContains("event=client.initialized transport=jdk-http");
        verify(getRequestedFor(urlEqualTo("/v1/conversations/" + id))
                .withHeader("Authorization", equalTo("Bearer workspace-key")));
        assertContract();
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

    @ParameterizedTest
    @EnumSource(Factory.class)
    @SetEnvironmentVariable(key = "NAMS_API_KEY", value = "workspace-key")
    @SetEnvironmentVariable(key = "NAMS_BASE_URL", value = "  https://environment.test/v1  ")
    void factoriesUseConfiguredEnvironmentUrl(Factory factory) {
        assertThat(factory.create()).isNotNull();

        logs.assertContains("event=client.initialized transport=jdk-http json=jackson3 baseUrl=https://environment.test/v1");
        assertNoCredentialsLogged();
        assertThat(getAllServeEvents()).isEmpty();
    }

    @Test
    @SetEnvironmentVariable(key = "NAMS_API_KEY", value = "workspace-key")
    @SetEnvironmentVariable(key = "NAMS_BASE_URL", value = "https://environment.test/v1")
    void configurationUsesEnvironmentKeyWithExplicitUrl(WireMockRuntimeInfo server) {
        stubConversationList();
        var client = MemoryClient.create(MemoryClientConfiguration.builder()
                .baseUrl(server.getHttpBaseUrl() + "/v1").build());

        assertConversationList(client, "workspace-key");
    }

    @ParameterizedTest
    @EnumSource(value = Factory.class, names = {"NO_ARGUMENT", "EMPTY_CONFIGURATION"})
    void factoriesRejectAbsentEnvironmentKey(Factory factory) {
        assertThat(System.getenv("NAMS_API_KEY")).isNull();
        assertMissingEnvironmentKey(factory);
    }

    @ParameterizedTest
    @EnumSource(value = Factory.class, names = {"NO_ARGUMENT", "EMPTY_CONFIGURATION"})
    @SetEnvironmentVariable(key = "NAMS_API_KEY", value = " \t")
    void factoriesRejectBlankEnvironmentKey(Factory factory) {
        assertThat(System.getenv("NAMS_API_KEY")).isBlank();
        assertMissingEnvironmentKey(factory);
    }

    @ParameterizedTest
    @EnumSource(Factory.class)
    @SetEnvironmentVariable(key = "NAMS_API_KEY", value = "workspace-key")
    void factoriesUseDefaultUrlForAbsentEnvironmentUrl(Factory factory) {
        assertThat(System.getenv("NAMS_BASE_URL")).isNull();
        assertDefaultUrl(factory);
    }

    @ParameterizedTest
    @EnumSource(Factory.class)
    @SetEnvironmentVariable(key = "NAMS_API_KEY", value = "workspace-key")
    @SetEnvironmentVariable(key = "NAMS_BASE_URL", value = " \t")
    void factoriesUseDefaultUrlForBlankEnvironmentUrl(Factory factory) {
        assertThat(System.getenv("NAMS_BASE_URL")).isBlank();
        assertDefaultUrl(factory);
    }

    @Test
    @SetEnvironmentVariable(key = "NAMS_API_KEY", value = "unused-key")
    @SetEnvironmentVariable(key = "NAMS_BASE_URL", value = "  https://environment.test/v1  ")
    void configurationKeyOverridesEnvironmentAndRetainsEnvironmentUrl() {
        var configuration = MemoryClientConfiguration.builder().apiKey("explicit-key").build();
        assertThat(configuration.apiKey()).isEqualTo("explicit-key");
        assertThat(configuration.baseUrl().toString()).isEqualTo("https://environment.test/v1");

        assertThat(MemoryClient.create(configuration)).isNotNull();

        logs.assertContains("baseUrl=https://environment.test/v1");
        assertNoCredentialsLogged();
        assertThat(getAllServeEvents()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/"})
    @SetEnvironmentVariable(key = "NAMS_API_KEY", value = "unused-key")
    @SetEnvironmentVariable(key = "NAMS_BASE_URL", value = "invalid environment URL")
    void configurationOverridesEnvironmentAndSupportsTrailingSlash(String suffix, WireMockRuntimeInfo server) {
        stubConversationList();
        var baseUrl = server.getHttpBaseUrl() + "/v1" + suffix;
        var client = MemoryClient.create(MemoryClientConfiguration.builder()
                .apiKey("explicit-key").baseUrl(baseUrl).build());

        assertConversationList(client, "explicit-key");
        logs.assertContains("baseUrl=" + baseUrl);
        assertThat(logs.getEvents()).extracting(LoggingEvent::getMessage)
                .allSatisfy(message -> assertThat(message).doesNotContain("invalid environment URL"));
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

    private void assertMissingEnvironmentKey(Factory factory) {
        assertThatThrownBy(factory::create)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("apiKey must not be blank");
        assertThat(logs.getEvents()).isEmpty();
    }

    private void assertDefaultUrl(Factory factory) {
        assertThat(factory.create()).isNotNull();
        logs.assertContains("event=client.initialized transport=jdk-http json=jackson3 baseUrl=https://memory.neo4jlabs.com/v1");
        assertNoCredentialsLogged();
        assertThat(getAllServeEvents()).isEmpty();
    }

    private static void stubConversationList() {
        stubFor(get(urlEqualTo("/v1/conversations?limit=10"))
                .willReturn(OpenApiContract.response("get", "/v1/conversations")));
    }

    private void assertConversationList(MemoryClient client, String apiKey) {
        assertThat(await(client.listConversations(new ListConversations(10)))).isNotEmpty();
        verify(getRequestedFor(urlEqualTo("/v1/conversations?limit=10"))
                .withHeader("Authorization", equalTo("Bearer " + apiKey)));
        assertNoCredentialsLogged();
        assertContract();
    }

    private void assertNoCredentialsLogged() {
        assertThat(logs.getEvents()).extracting(LoggingEvent::getMessage)
                .allSatisfy(message -> assertThat(message)
                        .doesNotContain("workspace-key", "explicit-key", "unused-key"));
    }

    private static void assertContract() {
        OpenApiContract.assertEveryExchangeMatchesTheContract();
        OpenApiContract.assertOnlyDeclaredRequestProperties();
    }

    enum Factory {
        NO_ARGUMENT {
            @Override MemoryClient create() {
                return MemoryClient.create();
            }
        },
        EMPTY_CONFIGURATION {
            @Override MemoryClient create() {
                return MemoryClient.create(MemoryClientConfiguration.builder().build());
            }
        },
        EXPLICIT_KEY {
            @Override MemoryClient create() {
                return MemoryClient.create(MemoryClientConfiguration.builder().apiKey("explicit-key").build());
            }
        };

        abstract MemoryClient create();
    }
}
