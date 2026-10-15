package org.neo4j.agentmemory.http.integrations;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;
import org.neo4j.agentmemory.conversation.Conversation;
import org.neo4j.agentmemory.conversation.CreateConversation;
import org.neo4j.agentmemory.conversation.ListConversations;
import org.neo4j.agentmemory.conversation.NewMessage;
import org.neo4j.agentmemory.entity.EntitySearch;
import org.neo4j.agentmemory.http.JsonCodecUnderTest;
import org.neo4j.agentmemory.reasoning.NewReasoningStep;
import org.neo4j.agentmemory.reasoning.NewToolCall;
import org.neo4j.agentmemory.reasoning.ToolCallStatus;
import org.neo4j.agentmemory.testsupport.OpenApiContract;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.neo4j.agentmemory.testsupport.TestAwaitSupport.await;

@ParameterizedClass
@MethodSource("clients")
class MemoryClientContractTest {
    private final HttpClientUnderTest httpClient;
    private final JsonCodecUnderTest codec;

    MemoryClientContractTest(HttpClientUnderTest httpClient, JsonCodecUnderTest codec) {
        this.httpClient = httpClient;
        this.codec = codec;
    }

    static Stream<Arguments> clients() {
        return Stream.concat(
                Arrays.stream(HttpClientUnderTest.values()).map(http -> arguments(http, JsonCodecUnderTest.JACKSON_3)),
                Stream.of(arguments(HttpClientUnderTest.JDK_DEFAULT, JsonCodecUnderTest.JACKSON_2)));
    }

    @RegisterExtension
    static final WireMockExtension WIRE_MOCK = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .configureStaticDsl(true)
            .build();

    private MemoryClient client;

    @BeforeEach
    void startFromACleanContract() {
        var configuration = httpClient.configure(MemoryClientConfiguration.builder()
                .baseUrl(WIRE_MOCK.baseUrl() + "/v1").apiKey("nams_contract-test-key")).build();
        client = codec == JsonCodecUnderTest.JACKSON_3
                ? MemoryClient.create(configuration)
                : codec.client(configuration);
    }

    @AfterEach
    void everyExchangeHonouredTheContract() {
        OpenApiContract.assertOnlyDeclaredRequestProperties();
        OpenApiContract.assertEveryExchangeMatchesTheContract();
    }

    @Test
    void createConversation() {
        stubFor(post(urlEqualTo("/v1/conversations"))
                .willReturn(OpenApiContract.response("post", "/v1/conversations")));

        var conversation = await(client.createConversation(new CreateConversation("alice")));

        assertThat(conversation.id()).isNotNull();
    }

    @Test
    void listConversations() {
        stubFor(get(urlPathEqualTo("/v1/conversations"))
                .willReturn(OpenApiContract.response("get", "/v1/conversations")));

        var conversations = await(client.listConversations(new ListConversations(25)));

        assertThat(conversations).isNotEmpty();
    }

    @Test
    void getConversation() {
        var conversationId = UUID.randomUUID();
        stubFor(get(urlEqualTo("/v1/conversations/" + conversationId))
                .willReturn(OpenApiContract.response("get", "/v1/conversations/{id}")));

        var conversation = await(client.getConversation(conversationId));

        assertThat(conversation.id()).isNotNull();
    }

    @Test
    void deleteConversation() {
        var conversation = conversation();
        stubFor(delete(urlEqualTo("/v1/conversations/" + conversation.id()))
                .willReturn(OpenApiContract.response("delete", "/v1/conversations/{id}")));

        assertThatNoException().isThrownBy(() -> await(conversation.delete()));
    }

    @Test
    void addMessage() {
        var conversation = conversation();
        stubFor(post(urlEqualTo("/v1/conversations/" + conversation.id() + "/messages"))
                .willReturn(OpenApiContract.response(
                        "post", "/v1/conversations/{id}/messages")));

        var message = await(conversation.addMessage(NewMessage.user("hello")));

        assertThat(message.id()).isNotNull();
    }

    @Test
    void addMessagesInBulk() {
        var conversation = conversation();
        stubFor(post(urlEqualTo("/v1/conversations/" + conversation.id() + "/messages/bulk"))
                .willReturn(OpenApiContract.response(
                        "post", "/v1/conversations/{id}/messages/bulk")));

        var messages = await(conversation
                .addMessages(List.of(NewMessage.user("hello"), NewMessage.assistant("hi"))));

        assertThat(messages).isNotEmpty();
    }

    @Test
    void listMessages() {
        var conversation = conversation();
        stubFor(get(urlPathEqualTo("/v1/conversations/" + conversation.id() + "/messages"))
                .willReturn(OpenApiContract.response("get", "/v1/conversations/{id}/messages")));

        assertThat(await(conversation.messages())).isNotEmpty();
    }

    @Test
    void getContext() {
        var conversation = conversation();
        stubFor(get(urlEqualTo("/v1/conversations/" + conversation.id() + "/context"))
                .willReturn(OpenApiContract.response("get", "/v1/conversations/{id}/context")));

        var context = await(conversation.context());

        assertThat(context.recentMessages()).isNotEmpty();
    }

    @Test
    void searchEntities() {
        stubFor(post(urlEqualTo("/v1/entities/search"))
                .willReturn(OpenApiContract.response("post", "/v1/entities/search")));

        assertThat(await(client.searchEntities(new EntitySearch("neo4j", "person", 5))))
                .isNotEmpty();
    }

    @Test
    void recordReasoningStep() {
        stubFor(post(urlEqualTo("/v1/reasoning/steps"))
                .willReturn(OpenApiContract.response("post", "/v1/reasoning/steps")));

        var step = await(conversation().recordStep(new NewReasoningStep("why", "what", "done")));

        assertThat(step.id()).isNotNull();
    }

    @Test
    void recordToolCall() {
        stubFor(post(urlEqualTo("/v1/reasoning/steps"))
                .willReturn(OpenApiContract.response("post", "/v1/reasoning/steps")));
        stubFor(post(urlEqualTo("/v1/reasoning/tool-calls"))
                .willReturn(OpenApiContract.response("post", "/v1/reasoning/tool-calls")));

        var step = await(conversation().recordStep(new NewReasoningStep("why", "what")));
        var call = await(step.recordToolCall(NewToolCall.builder("search", "{}")
                        .output("{}")
                        .duration(Duration.ofMillis(150))
                        .status(ToolCallStatus.SUCCESS)
                        .build())
                );

        assertThat(call.id()).isNotNull();
    }

    @Test
    void getReasoningTrace() {
        var conversation = conversation();
        stubFor(get(urlEqualTo("/v1/reasoning/trace/" + conversation.id()))
                .willReturn(OpenApiContract.response(
                        "get", "/v1/reasoning/trace/{conversationId}")));

        assertThat(await(conversation.trace()).steps()).isNotEmpty();
    }

    @Test
    void explainReasoningStep() {
        stubFor(post(urlEqualTo("/v1/reasoning/steps"))
                .willReturn(OpenApiContract.response("post", "/v1/reasoning/steps")));
        var step = await(conversation().recordStep(new NewReasoningStep("why", "what")));
        stubFor(get(urlEqualTo("/v1/reasoning/explain/" + step.id()))
                .willReturn(OpenApiContract.response("get", "/v1/reasoning/explain/{stepId}")));

        assertThat(await(step.explanation()).toolCalls()).isNotEmpty();
    }

    private Conversation conversation() {
        stubFor(post(urlEqualTo("/v1/conversations"))
                .willReturn(OpenApiContract.response("post", "/v1/conversations")));
        return await(client.createConversation(new CreateConversation()));
    }

}
