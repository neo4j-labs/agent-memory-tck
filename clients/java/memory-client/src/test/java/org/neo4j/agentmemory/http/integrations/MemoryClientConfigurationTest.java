package org.neo4j.agentmemory.http.integrations;

import io.github.netmikey.logunit.api.LogCapturer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.neo4j.agentmemory.MemoryClientConfiguration;
import org.junitpioneer.jupiter.ClearEnvironmentVariable;
import org.junitpioneer.jupiter.SetEnvironmentVariable;
import org.slf4j.event.Level;

import java.net.URI;
import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ClearEnvironmentVariable(key = "NAMS_API_KEY")
@ClearEnvironmentVariable(key = "NAMS_BASE_URL")
class MemoryClientConfigurationTest {
    @RegisterExtension
    final LogCapturer logs = LogCapturer.create().captureForType(MemoryClientConfiguration.class);

    @Test
    void rejectsNullLangChain4jArguments() {
        assertThatThrownBy(() -> MemoryClientConfiguration.builder()
                .langChain4jHttpClient(null, HttpClientUnderTest.TEST_EXECUTOR))
                .isInstanceOf(NullPointerException.class).hasMessage("httpClient");
        assertThatThrownBy(() -> MemoryClientConfiguration.builder()
                .langChain4jHttpClient(dev.langchain4j.http.client.jdk.JdkHttpClient.builder().build(), null))
                .isInstanceOf(NullPointerException.class).hasMessage("executor");
    }

    @Test
    void lastHttpClientSetterWins() {
        var restClient = org.springframework.web.client.RestClient.create();
        var httpClient = dev.langchain4j.http.client.jdk.JdkHttpClient.builder().build();
        var executor = HttpClientUnderTest.TEST_EXECUTOR;
        var webClient = org.springframework.web.reactive.function.client.WebClient.create();
        var transport = org.neo4j.agentmemory.http.internal.HttpTransports.select(HttpClient.newHttpClient());
        var builder = MemoryClientConfiguration.builder().baseUrl("https://memory.test/v1").apiKey("key");
        assertThat(builder.restClient(restClient, executor).langChain4jHttpClient(httpClient, executor)
                .build().httpTransport().name()).isEqualTo("langchain4j-http");
        assertThat(builder.langChain4jHttpClient(httpClient, executor).restClient(restClient, executor)
                .build().httpTransport().name()).isEqualTo("spring-rest-client");
        assertThat(builder.langChain4jHttpClient(httpClient, executor).jdkHttpClient(HttpClient.newHttpClient())
                .build().httpTransport().name()).isEqualTo("jdk-http");
        assertThat(builder.restClient(restClient, executor).webClient(webClient)
                .build().httpTransport().name()).isEqualTo("spring-web-client");
        assertThat(builder.webClient(webClient).httpTransport(transport)
                .build().httpTransport()).isSameAs(transport);
        assertThat(builder.httpTransport(transport).jdkHttpClient(HttpClient.newHttpClient())
                .build().httpTransport().name()).isEqualTo("jdk-http");
    }

    @Test
    void rejectsNullWebClient() {
        assertThatThrownBy(() -> MemoryClientConfiguration.builder().webClient(null))
                .isInstanceOf(NullPointerException.class).hasMessage("webClient");
    }

    @Test
    void rejectsNullHttpTransport() {
        assertThatThrownBy(() -> MemoryClientConfiguration.builder().httpTransport(null))
                .isInstanceOf(NullPointerException.class).hasMessage("httpTransport");
    }

    @Test
    void rejectsNullRestClientArguments() {
        assertThatThrownBy(() -> MemoryClientConfiguration.builder()
                .restClient(null, HttpClientUnderTest.TEST_EXECUTOR))
                .isInstanceOf(NullPointerException.class).hasMessage("restClient");
        assertThatThrownBy(() -> MemoryClientConfiguration.builder()
                .restClient(org.springframework.web.client.RestClient.create(), null))
                .isInstanceOf(NullPointerException.class).hasMessage("executor");
    }

    @Test
    void rejectsNullJdkHttpClient() {
        assertThatThrownBy(() -> MemoryClientConfiguration.builder().jdkHttpClient(null))
                .isInstanceOf(NullPointerException.class).hasMessage("httpClient");
    }

    @Test
    void configurationWithoutHttpClientDefaultsToJdkTransport() {
        assertThat(MemoryClientConfiguration.builder().baseUrl("https://memory.test/v1")
                .apiKey("key").build().httpTransport().name()).isEqualTo("jdk-http");
    }

    @Test
    void jdkHttpClientSelectsJdkTransport() {
        assertThat(MemoryClientConfiguration.builder().baseUrl("https://memory.test/v1")
                .apiKey("key").jdkHttpClient(HttpClient.newHttpClient())
                .build().httpTransport().name()).isEqualTo("jdk-http");
    }

    @Test
    void rejectsNullOverridesInSetters() {
        assertThatThrownBy(() -> MemoryClientConfiguration.builder().apiKey(null))
                .isInstanceOf(NullPointerException.class).hasMessage("apiKey");
        assertThatThrownBy(() -> MemoryClientConfiguration.builder().baseUrl(null))
                .isInstanceOf(NullPointerException.class).hasMessage("baseUrl");
    }

    @Test
    @SetEnvironmentVariable(key = "NAMS_API_KEY", value = "unused-key")
    @SetEnvironmentVariable(key = "NAMS_BASE_URL", value = "invalid URL")
    void buildsConfigurationWithExplicitValues() {
        var configuration = MemoryClientConfiguration.builder()
                .apiKey("workspace-key")
                .baseUrl("  https://memory.test/v1  ")
                .build();

        assertThat(configuration.apiKey()).isEqualTo("workspace-key");
        assertThat(configuration.baseUrl()).isEqualTo(URI.create("https://memory.test/v1"));
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsBlankKeyWhenBuilding(String apiKey) {
        var builder = MemoryClientConfiguration.builder().baseUrl("https://memory.test/v1").apiKey(apiKey);
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("apiKey must not be blank");
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {" ", "not a URL", "/v1", "ftp://memory.test/v1", "https:///v1",
            "https://user:password@memory.test/v1", "https://memory.test/v1?key=secret",
            "https://memory.test/v1#fragment"})
    void rejectsInvalidBaseUrlWhenBuilding(String baseUrl) {
        var builder = MemoryClientConfiguration.builder().apiKey("workspace-key").baseUrl(baseUrl);
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("baseUrl must be an absolute HTTP(S) URL without user info, query, or fragment");
    }

    @Test
    @SetEnvironmentVariable(key = "NAMS_API_KEY", value = "environment-key")
    @SetEnvironmentVariable(key = "NAMS_BASE_URL", value = "  https://environment.test/v1  ")
    void emptyBuilderResolvesEnvironmentWhenBuilt() {
        var configuration = MemoryClientConfiguration.builder().build();

        assertThat(configuration.apiKey()).isEqualTo("environment-key");
        assertThat(configuration.baseUrl()).isEqualTo(URI.create("https://environment.test/v1"));
        assertThat(logs.getEvents()).isEmpty();
    }

    @Test
    @SetEnvironmentVariable(key = "NAMS_API_KEY", value = "environment-key")
    void defaultsBaseUrlWhenEnvironmentIsAbsent() {
        assertDefaultBaseUrlAndWarning();
    }

    @Test
    @SetEnvironmentVariable(key = "NAMS_API_KEY", value = "environment-key")
    @SetEnvironmentVariable(key = "NAMS_BASE_URL", value = " \t")
    void defaultsBaseUrlWhenEnvironmentIsBlank() {
        assertDefaultBaseUrlAndWarning();
    }

    @Test
    void rejectsAbsentEnvironmentKeyDuringBuild() {
        assertThatThrownBy(() -> MemoryClientConfiguration.builder().build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("apiKey must not be blank");
        assertThat(logs.getEvents()).isEmpty();
    }

    @Test
    @SetEnvironmentVariable(key = "NAMS_API_KEY", value = " \t")
    void rejectsBlankEnvironmentKeyDuringBuild() {
        assertThatThrownBy(() -> MemoryClientConfiguration.builder().build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("apiKey must not be blank");
        assertThat(logs.getEvents()).isEmpty();
    }

    @Test
    @SetEnvironmentVariable(key = "NAMS_API_KEY", value = "environment-key")
    @SetEnvironmentVariable(key = "NAMS_BASE_URL", value = "invalid URL")
    void rejectsInvalidEnvironmentUrlDuringBuild() {
        assertThatThrownBy(() -> MemoryClientConfiguration.builder().build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("baseUrl must be an absolute HTTP(S) URL without user info, query, or fragment");
        assertThat(logs.getEvents()).isEmpty();
    }

    @Test
    void builtConfigurationDoesNotChangeWhenBuilderIsReused() {
        var builder = MemoryClientConfiguration.builder()
                .apiKey("first-key").baseUrl("https://first.test/v1");
        var first = builder.build();
        var second = builder.apiKey("second-key").baseUrl("https://second.test/v1").build();

        assertThat(first.apiKey()).isEqualTo("first-key");
        assertThat(first.baseUrl()).isEqualTo(URI.create("https://first.test/v1"));
        assertThat(second.apiKey()).isEqualTo("second-key");
        assertThat(second.baseUrl()).isEqualTo(URI.create("https://second.test/v1"));
    }

    private void assertDefaultBaseUrlAndWarning() {
        var configuration = MemoryClientConfiguration.builder().build();

        assertThat(configuration.apiKey()).isEqualTo("environment-key");
        assertThat(configuration.baseUrl()).isEqualTo(URI.create("https://memory.neo4jlabs.com/v1"));
        assertThat(logs.getEvents()).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getMessage())
                    .contains("NAMS_BASE_URL is absent or blank; using default base URL",
                            "https://memory.neo4jlabs.com/v1")
                    .doesNotContain("environment-key");
        });
    }
}
