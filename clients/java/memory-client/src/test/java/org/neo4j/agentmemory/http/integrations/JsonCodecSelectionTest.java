package org.neo4j.agentmemory.http.integrations;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import io.github.netmikey.logunit.api.LogCapturer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;
import org.neo4j.agentmemory.conversation.MessageRole;
import org.neo4j.agentmemory.conversation.NewMessage;
import org.neo4j.agentmemory.testsupport.OpenApiContract;

import java.util.UUID;
import java.util.concurrent.Executors;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.neo4j.agentmemory.testsupport.TestAwaitSupport.await;

@WireMockTest
class JsonCodecSelectionTest {
    @RegisterExtension
    final LogCapturer logs = LogCapturer.create().captureForType(MemoryClient.class);

    @Test
    void bothMajorsPreferJackson3(WireMockRuntimeInfo server) throws Exception {
        assertThat(Class.forName("com.fasterxml.jackson.databind.json.JsonMapper")).isNotNull();
        assertThat(Class.forName("tools.jackson.databind.json.JsonMapper")).isNotNull();
        var id = stubReadAndWrite();
        var client = MemoryClient.create(configuration(server).build());

        assertReadAndWrite(client, id);

        logs.assertContains("event=client.initialized transport=jdk-http json=jackson3");
    }

    @Test
    @Tag("jackson2-only")
    void jackson2OnlyWithLangChain4j(WireMockRuntimeInfo server) throws Exception {
        assertThat(Class.forName("com.fasterxml.jackson.databind.json.JsonMapper")).isNotNull();
        assertClassAbsent("tools.jackson.databind.json.JsonMapper");
        assertClassAbsent("tools.jackson.core.JsonFactory");
        var id = stubReadAndWrite();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var client = MemoryClient.create(configuration(server)
                    .langChain4jHttpClient(JdkHttpClient.builder().build(), executor).build());

            assertReadAndWrite(client, id);

            logs.assertContains("event=client.initialized transport=langchain4j-http json=jackson2");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Tag("broken-jackson3")
    void brokenJackson3FallsBackToJackson2(WireMockRuntimeInfo server) throws Exception {
        // Check the resource without linking databind to its deliberately absent core.
        assertThat(getClass().getClassLoader().getResource("tools/jackson/databind/json/JsonMapper.class"))
                .isNotNull();
        assertClassAbsent("tools.jackson.core.JsonFactory");
        assertThat(Class.forName("com.fasterxml.jackson.databind.json.JsonMapper")).isNotNull();
        var id = stubReadAndWrite();
        var client = MemoryClient.create(configuration(server).build());

        assertReadAndWrite(client, id);

        logs.assertContains("event=client.initialized transport=jdk-http json=jackson2");
    }

    private static MemoryClientConfiguration.Builder configuration(WireMockRuntimeInfo server) {
        return MemoryClientConfiguration.builder()
                .apiKey("workspace-key").baseUrl(server.getHttpBaseUrl() + "/v1");
    }

    private static void assertClassAbsent(String className) {
        assertThatThrownBy(() -> Class.forName(className)).isInstanceOf(ClassNotFoundException.class);
    }

    private static UUID stubReadAndWrite() throws Exception {
        var response = OpenApiContract.response("get", "/v1/conversations/{id}");
        var id = UUID.fromString(JsonMapper.builder().build()
                .readTree(response.build().getBody()).get("id").asText());
        var path = "/v1/conversations/" + id;
        stubFor(get(urlEqualTo(path)).willReturn(response));
        stubFor(post(urlEqualTo(path + "/messages"))
                .willReturn(OpenApiContract.response("post", "/v1/conversations/{id}/messages")));
        return id;
    }

    private static void assertReadAndWrite(MemoryClient client, UUID id) {
        var conversation = await(client.getConversation(id));
        var message = await(conversation.addMessage(NewMessage.user("zażółć")));
        assertThat(conversation.id()).isEqualTo(id);
        assertThat(message.role()).isEqualTo(MessageRole.USER);

        var path = "/v1/conversations/" + id;
        verify(getRequestedFor(urlEqualTo(path))
                .withHeader("Authorization", equalTo("Bearer workspace-key")));
        verify(postRequestedFor(urlEqualTo(path + "/messages"))
                .withHeader("Authorization", equalTo("Bearer workspace-key"))
                .withRequestBody(matchingJsonPath("$.role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.content", equalTo("zażółć"))));
        OpenApiContract.assertEveryExchangeMatchesTheContract();
        OpenApiContract.assertOnlyDeclaredRequestProperties();
    }
}
