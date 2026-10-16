# Neo4j Agent Memory Java client

A non-opinionated Java client for the hosted Neo4j Agent Memory Service. The core artifact is `org.neo4j:agent-memory-client:<version>`. It uses the JDK HTTP client by default and can reuse an application's Spring `RestClient` or `WebClient`, its LangChain4j `HttpClient`, or any other client wrapped in a custom transport. The framework dependencies are optional. The client uses whichever Jackson major the application provides at runtime, preferring Jackson 3 when both are usable.

The minimum version of Java is Java 17 (to align with the minimal requirement for Langchain4j or Spring Framework).

## Installation

The minimum setup to instantiate memory client and connect to `https://memory.neo4jlabs.com/` is:

```java
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;

var client = MemoryClient.create();
var conversation = client.createConversation(
        new CreateConversation("<user>", Map.of("title", "Plan my next trip"))
    );
```

The required dependencies are `agent-memory-client` itself with a required `jackson` for JSON processing. The `jackson` itself is usually supplied by the agentic framework.

```xml
<dependency>
  <groupId>org.neo4j</groupId>
  <artifactId>agent-memory-client</artifactId>
  <version>${nams-version}</version>
</dependency>
<dependency>
  <groupId>tools.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
  <version>3.1.5</version>
</dependency>
```

`com.fasterxml.jackson.core:jackson-databind` 2.19.0+ works instead of Jackson 3.

Verified supported application frameworks are:

- Spring AI 2.0+ (Jackson 3.1.4+, Spring Framework 7.0+).
- LangChain4j 1.0+ (its `langchain4j-core` brings Jackson 2.19+).
- Standalone applications that declare either major.

## Public API

List one user's Conversations with `client.listConversations(new ListConversations(25, "alice"))`.

Delete one user's User Memory with one service call per invocation. An incomplete
result means the service ran out of time; the caller decides whether to repeat:

```java
var deletion = MemoryClient.await(client.deleteUserMemory("alice"));
while (!deletion.complete()) {
    deletion = MemoryClient.await(client.deleteUserMemory("alice"));
}
```

`MemoryClient.create()` uses the default configuration, that resolves environment settings: `MEMORY_API_KEY` must contain a nonblank Workspace API key. `MEMORY_ENDPOINT` is optional: when absent or blank, the client uses `https://memory.neo4jlabs.com/v1` and emits a WARN identifying the default URL on each successful build. Explicit builder settings take precedence over environment settings. The default JDK http transport is used.

The client expects a **Workspace API key** bound to the target workspace. It does not yet support an Admin key combined with an explicitly provided workspace ID.

The only other factory is `MemoryClient.create(MemoryClientConfiguration)`. Use the fluent builder to override either setting; omitted settings still come from the environment:

```java
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;

var configuration = MemoryClientConfiguration.builder()
        .apiKey("<nams-api-key>")
        .baseUrl("https://memory.neo4jlabs.com/v1")
        .build();
var client = MemoryClient.create(configuration);
```

`apiKey(String)` and `baseUrl(String)` override their environment variables.

MemoryClient defines all supported domain operations. Applications can use these operations  directly or through the live Conversation handle. Every returned handle is bound to the client that produced it. Alternate adapters implement MemoryClient and can construct those same handles from domain values.

| Package | Contents |
| --- | --- |
| org.neo4j.agentmemory | MemoryClient, MemoryClientConfiguration, default factories, and await helpers |
| org.neo4j.agentmemory.conversation | Conversation, requests, Messages, and Conversation Context |
| org.neo4j.agentmemory.reasoning | Reasoning Steps, traces, explanations, and Tool Calls |
| org.neo4j.agentmemory.entity | Entity values and search inputs |
| org.neo4j.agentmemory.user | User Memory deletion results |
| org.neo4j.agentmemory.exception | Client-owned failures |
| org.neo4j.agentmemory.http | The HTTP transport extension point (`HttpTransport`, `HttpCall`, `HttpResult`) and the internal `HttpMemoryClient` |

```java
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.neo4j.agentmemory.conversation.CreateConversation;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.conversation.NewMessage;

import static org.neo4j.agentmemory.MemoryClient.await;

// Inside a method that declares throws Exception:
var client = MemoryClient.create();
var conversation = await(client.createConversation(
            new CreateConversation("<user>", Map.of("title", "Plan my next trip"))
        ));
conversation.addMessage(NewMessage.user("Find hotels in Catalona"));
var recent = await(conversation.messages(20)); //get most recent messages
```

### Supplying and configuring http transport

The library does not enforce a single HTTP client and allows supplying own's configuration (for example with a desired observability or headers set-up). The supplied client is used as configured, including its executor, proxy,  timeouts, and TLS settings. The last HTTP client setter wins. The memory client never closes injected clients or executors; their lifecycle belongs to the application. The client does not perform any automatic write retries.

#### Using a JDK Http Client

Use `jdkHttpClient(HttpClient)` to supply a configured JDK HTTP client:

```java
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;

var executor = Executors.newCachedThreadPool();
var httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .executor(executor)
        .build();
var client = MemoryClient.create(MemoryClientConfiguration.builder()
        .apiKey("<workspace-api-key>")
        .baseUrl("https://memory.neo4jlabs.com/v1")
        .jdkHttpClient(httpClient)
        .build());
```

#### Using a Spring RestClient

Supply an application-built `RestClient` and an executor for its blocking exchanges. For example, in Spring Boot:

```java
@Bean
MemoryClient memoryClient(RestClient.Builder restClientBuilder, AsyncTaskExecutor applicationTaskExecutor) {
    return MemoryClient.create(MemoryClientConfiguration.builder()
            .restClient(restClientBuilder.build(), applicationTaskExecutor)
            .build());
}
```

The client is used as configured. Its interceptors, observation registry, request-factory timeouts, TLS settings, default headers, cookies, and `defaultRequest` customizations all apply to memory requests. Observations are tagged with route templates such as `/v1/conversations/{conversationId}`. The memory configuration supplies absolute service URLs, so the RestClient's base URL does not apply, and the memory client's `Authorization`, `Accept`, and `Content-Type` headers replace same-named headers. Everything else you configure is sent to the memory service, including a default API version: a path-segment version inserter rewrites memory URLs too. Inject a client meant for the memory service rather than one carrying another service's credentials or tenant headers. Status handlers do not apply; non-success responses retain their headers and are mapped to the memory client's exceptions.

Requests with String URI variables, such as user IDs, use the memory client's strict percent encoding while retaining template observation tags. For these requests, a Spring `UriBuilderFactory` still parses the template but does not expand variables or transform the resulting URI. Its custom URI transformations therefore do not apply. Requests containing only UUID and integer variables continue using the application's factory expansion.

Blocking exchanges run on the supplied executor, which holds one thread per request in flight; the returned futures still never block the caller. On Java 21 with `spring.threads.virtual.enabled=true`, Spring Boot's `applicationTaskExecutor` runs on virtual threads, which makes those waiting threads cheap. The memory client closes neither the RestClient nor the executor, and the last HTTP client setter wins. 

#### Using a Spring WebClient

Supply an application-built `WebClient`. Its exchanges do not block, so it needs no executor. For example, in a Spring Boot WebFlux application:

```java
@Bean
MemoryClient memoryClient(WebClient.Builder webClientBuilder) {
    return MemoryClient.create(MemoryClientConfiguration.builder()
            .webClient(webClientBuilder.build())
            .build());
}
```

The client is used as configured, as a RestClient is. Its filters, observation registry, connector settings, default headers, cookies, and `defaultRequest` customizations apply, and observations are tagged with route templates. Its base URL and status handlers do not apply, and the memory headers replace same-named headers.

Response bodies are buffered through the client's codecs, so its in-memory buffer limit bounds memory responses: 256 KB by default, or `spring.codec.max-in-memory-size` in Spring Boot. A larger response, such as a long message history, fails with `MemoryClientException` caused by `DataBufferLimitException`. Raise the limit if you read large histories.

#### Using a LangChain4j HttpClient

Supply an application-built LangChain4j HTTP client and an executor for its blocking exchanges:

```java
import dev.langchain4j.http.client.HttpClientBuilderLoader;
import java.time.Duration;

var httpClient = HttpClientBuilderLoader.loadHttpClientBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .readTimeout(Duration.ofSeconds(30))
        .build();
var client = MemoryClient.create(MemoryClientConfiguration.builder()
        .langChain4jHttpClient(httpClient, executor)
        .build());
```

The client is used as configured, including its logging and network settings.  LangChain4j 1.0 or later is required; the application supplies `dev.langchain4j:langchain4j-http-client` and an implementation such as `dev.langchain4j:langchain4j-http-client-jdk`.

LangChain4j discards headers on non-2xx responses. Service exceptions retain the status and body excerpt but have no response headers or content type, and operation events omit `requestId`. Use a Spring client when those diagnostics matter.

#### Bringing your own HTTP transport

To use an HTTP client without a built-in setter, such as OkHttp, Apache  HttpClient, or Vert.x, wrap it in an implementation of `org.neo4j.agentmemory.http.HttpTransport` and pass it to
`MemoryClientConfiguration.Builder.httpTransport(...)`. The `org.neo4j.agentmemory.http` package is the extension point: `HttpTransport`, the `HttpCall` it receives, and the `HttpResult` it returns. `HttpMemoryClient`, in the same package, is the memory client's implementation and not part of the extension point.

The memory client keeps routes, authentication, JSON, failure mapping, and logging; a transport only moves bytes. Each memory request reaches `send(HttpCall)` as a method, an absolute URI, the memory headers, and a body. `uri()` gives the expanded URI; `uriTemplate()` and `uriVariables()` give the route template for clients that tag requests by route. The transport sends the request through the wrapped client and completes the returned future with the response's status, headers, and body bytes.

A transport must:

- return from `send` without waiting on I/O. Adapt an asynchronous client's
  callback or future; run a blocking client's exchange on an executor the
  application supplies.
- complete with an `HttpResult` for every HTTP response, including 4xx and 5xx.
  The memory client turns those into `MemoryServiceException` with their status
  and headers; a failed future loses both.
- send to `call.uri()` with `call.headers()`, letting them replace same-named
  headers the wrapped client would add. Anything else the wrapped client adds,
  such as default headers, cookies, or interceptor headers, is sent as configured.
- send `HttpCall.NoBody` as no request body, and the `body()` bytes of any other
  body unchanged.
- perform no retries, and never close the wrapped client or executor.
- treat cancellation as best effort. Cancelling an operation's future does not
  currently reach the transport.
- report a short, kebab-case `name()`, such as `okhttp`, that does not reuse a
  built-in name. It appears as `transport=` in the `client.initialized` event.

Failures need no translation: an exception thrown by `send` or a failed future becomes a `MemoryClientException` that keeps the original exception as its cause. To check a transport, run it against a stub server such as WireMock and confirm that requests carry the memory headers and that non-success responses arrive as `MemoryServiceException`.

## Blocking helper

`MemoryClient.await(future)` returns a future's value, including null for a successful void operation. `MemoryClient.await(future, Duration)` supplies a timeout for that call.`NAMS_AWAIT_TIMEOUT_SECONDS` configures the default wait in positive whole seconds. It is read once on first use of `await(future)` and defaults to 30 seconds when absent, blank, malformed, nonpositive, or too large. An explicit positive `Duration` bypasses it and supports subsecond waits.

```java
import static org.neo4j.agentmemory.MemoryClient.await;

import java.time.Duration;
import java.util.UUID;
import org.neo4j.agentmemory.MemoryClient;

public final class ReadConversationMessages {
    public static void main(String[] args) {
        var client = MemoryClient.create();
        var conversationId = UUID.fromString(args[0]);

        // Uses NAMS_AWAIT_TIMEOUT_SECONDS, falling back to 30 seconds.
        var conversation = await(client.getConversation(conversationId));

        // Overrides the default for this call and returns List<Message> directly.
        var messages = await(conversation.messages(20), Duration.ofSeconds(5));
        messages.forEach(message -> System.out.println(message.content()));
    }
}
```

## Logging

The client uses JDK `System.Logger`. To capture these events with Logback, add the JDK Platform Logging bridge and Logback backend to the **application's** dependencies. The bridge routes `System.Logger` through SLF4J. [SLF4J documents the bridge here](https://www.slf4j.org/manual.html#jep264).

```xml
<dependency>
  <groupId>org.slf4j</groupId>
  <artifactId>slf4j-jdk-platform-logging</artifactId>
  <version>2.0.20</version>
  <scope>runtime</scope>
</dependency>
<dependency>
  <groupId>ch.qos.logback</groupId>
  <artifactId>logback-classic</artifactId>
  <version>1.5.38</version>
  <scope>runtime</scope>
</dependency>
```

The log entries are structured and follow the example below: `event=client.initialized transport=jdk-http json=jackson3 baseUrl=https://memory.neo4jlabs.com/v1`.
The `transport` is `jdk-http`, `spring-rest-client`, `spring-web-client`, `langchain4j-http`, or a custom transport's name, depending on the configured HTTP client. The `json` field is `jackson3` or `jackson2`, depending on the usable codecs on the classpath. 

Additionally, at DEBUG the client can emit these diagnostic summaries (values are illustrative):

```text
event=operation.started operation=listConversations callId=7
event=operation.completed operation=listConversations callId=7 outcome=success durationMs=12 status=200 requestId=req-123 resultCount=2
event=operation.started operation=getConversation callId=8
event=operation.completed operation=getConversation callId=8 outcome=failure durationMs=9 status=404 phase=http errorType=MemoryServiceException
```
