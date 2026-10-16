package org.neo4j.agentmemory;

import org.neo4j.agentmemory.conversation.*;
import org.neo4j.agentmemory.entity.Entity;
import org.neo4j.agentmemory.entity.EntitySearch;
import org.neo4j.agentmemory.exception.MemoryClientException;
import org.neo4j.agentmemory.exception.MemoryServiceException;
import org.neo4j.agentmemory.exception.MissingJsonCodecException;
import org.neo4j.agentmemory.exception.ResponseDecodingException;
import org.neo4j.agentmemory.http.HttpMemoryClient;
import org.neo4j.agentmemory.reasoning.*;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Provides asynchronous operations on hosted agent memory.
 *
 * <p>Implementations return domain values and live handles. Every returned
 * {@link Conversation} and {@link ReasoningStep}, including steps inside
 * traces and explanations, is bound to the client that produced it.
 * Handle operations use that client and the handle's stored identity.
 *
 * <p>Returned collections are immutable snapshots that preserve service
 * order. A live handle's detail fields describe the response that created
 * it; fetch the handle again to observe later changes to those fields.
 * Successful writes do not establish that extraction or enrichment is ready.
 *
 * <p>Remote failures complete the returned future exceptionally. Local
 * validation can throw before a future is returned, as documented by each
 * method. Implementations must preserve the documented validation timing,
 * result ordering, snapshot semantics, and client-owned failure contract.
 * The HTTP adapters use {@link MemoryServiceException} for a
 * non-success HTTP response, {@link ResponseDecodingException} for an
 * invalid success payload, and {@link MemoryClientException} for encoding
 * or transport failures.
 *
 * <p>Writes are not retried automatically. Cancelling a future does not
 * confirm transport cancellation. The {@code await} helpers bound the
 * caller's wait without cancelling or completing the supplied future.
 *
 * <p>Authentication requires a Workspace API key bound to the target workspace.
 * Admin keys with an explicitly supplied workspace ID are not yet supported.
 *
 * <p>The static {@code create} methods use the configured HTTP client,
 * or a new default JDK HTTP client when none is configured.
 * Another adapter can implement this interface and bind the same live
 * handle classes to itself.
 */
public interface MemoryClient {
    /**
     * Waits for a supplied future using NAMS_AWAIT_TIMEOUT_SECONDS, or 30 seconds.
     * The environment default is resolved once on first use. Missing or invalid
     * configuration logs its fallback through System.Logger.
     *
     * @param future the operation to await; may already have started
     * @param <T> the result type
     * @return the result, including null for successful void operations
     * @throws NullPointerException if future is null
     * @throws MemoryClientException on timeout, interruption, or a checked failure
     * @throws java.util.concurrent.CancellationException if the operation was cancelled
     * @see #await(CompletableFuture, Duration)
     */
    static <T> T await(CompletableFuture<T> future) {
        return AwaitSupport.await(future);
    }

    /**
     * Waits interruptibly for a supplied future with an explicit timeout.
     * This overload bypasses environment configuration and default-timeout logging.
     * Waiting starts here, rather than when the operation started. Timeout and
     * interruption leave the future running; interruption restores the thread's flag.
     * Async wrappers are removed, preserving client exceptions, other runtime
     * exceptions, and errors. Checked failures become MemoryClientException causes.
     *
     * @param future the operation to await; may already have started
     * @param timeout a positive duration representable as a long number of nanoseconds
     * @param <T> the result type
     * @return the result, including null for successful void operations
     * @throws NullPointerException if future is null
     * @throws IllegalArgumentException if timeout is null, nonpositive, or too large
     * @throws MemoryClientException on timeout, interruption, or a checked failure
     * @throws java.util.concurrent.CancellationException if the operation was cancelled
     */
    static <T> T await(CompletableFuture<T> future, Duration timeout) {
        return AwaitSupport.await(future, timeout);
    }

    /**
     * Constructs a client with environment settings and a new default JDK HTTP client.
     * Equivalent to create(MemoryClientConfiguration.builder().build()).
     * MEMORY_API_KEY is required.
     * MEMORY_ENDPOINT defaults to
     * https://memory.neo4jlabs.com/v1 when absent or blank.
     * Construction logs the base URL and makes no remote request.
     *
     * @return a client using the resolved environment settings
     * @throws IllegalArgumentException if environment settings are invalid
     * @throws MissingJsonCodecException if neither Jackson 3 nor Jackson 2 is usable
     * @see MemoryClientConfiguration.Builder#build()
     */
    static MemoryClient create() {
        return create(MemoryClientConfiguration.builder().build());
    }

    /**
     * Constructs a client from validated configuration using the configured HTTP
     * client or transport, or the default JDK HTTP client when none is configured.
     * Logs the base URL without checking remote reachability or authentication.
     *
     * @param configuration immutable settings produced by the configuration builder
     * @return a client using the supplied settings
     * @throws NullPointerException if configuration is null
     * @throws MissingJsonCodecException if neither Jackson 3 nor Jackson 2 is usable
     */
    static MemoryClient create(MemoryClientConfiguration configuration) {
        return HttpMemoryClient.create(Objects.requireNonNull(configuration, "configuration"));
    }

    /**
     * Creates a Conversation with a service-generated identity.
     * The request can contain an optional user ID and string metadata.
     * The returned handle retains the response snapshot and this client.
     *
     * @param request the initial user association and metadata
     * @return a future containing the new live Conversation
     */
    CompletableFuture<Conversation> createConversation(CreateConversation request);

    /**
     * Reads one service-selected set of Conversations using the supplied limit.
     * Preserves service order and performs no automatic pagination or local
     * sorting. Each returned handle retains its available response details
     * and is bound to this client.
     *
     * <p>An explicit empty result succeeds. A missing or null required
     * conversations array in an HTTP response fails the future with
     * {@link ResponseDecodingException}.
     *
     * @param request the requested list limit
     * @return a future containing an immutable list of live Conversations
     */
    CompletableFuture<List<Conversation>> listConversations(ListConversations request);

    /**
     * Fetches a Conversation and its currently available details.
     * Fetch again to observe later changes to its metadata or display fields.
     * The returned handle is bound to this client.
     *
     * @param conversationId the identity of the Conversation to fetch
     * @return a future containing the live Conversation
     */
    CompletableFuture<Conversation> getConversation(UUID conversationId);

    /**
     * Searches for Entities with a limit of 10 and no type filter.
     * Delegates to {@link #searchEntities(EntitySearch)}.
     *
     * @param query the search text
     * @return a future containing an immutable list of matching Entities
     */
    default CompletableFuture<List<Entity>> searchEntities(String query) {
        return searchEntities(new EntitySearch(query));
    }

    /**
     * Searches for Entities using the supplied query, limit, and optional
     * type filter. Entity types are service-provided strings.
     * Preserves service result order; no matches produce an empty list.
     *
     * @param search the query, limit, and optional type filter
     * @return a future containing an immutable list of matching Entities
     */
    CompletableFuture<List<Entity>> searchEntities(EntitySearch search);

    /**
     * Persists one Message in the identified Conversation.
     * The client does not retry the write automatically.
     *
     * @param conversationId the Conversation that receives the Message
     * @param message the role and content to persist
     * @return a future containing the persisted Message
     */
    CompletableFuture<Message> addMessage(UUID conversationId, NewMessage message);

    /**
     * Submits an ordered batch of Messages to the identified Conversation.
     * Copies the input list before asynchronous work so later caller changes
     * to the list do not change the submitted batch. Preserves service result
     * order and does not retry the write automatically.
     *
     * @param conversationId the Conversation that receives the Messages
     * @param messages the ordered Message inputs
     * @return a future containing an immutable list of returned Messages
     * @throws NullPointerException if messages is null or contains null;
     *         thrown before this method returns a future
     */
    CompletableFuture<List<Message>> addMessages(
            UUID conversationId, List<NewMessage> messages);

    /**
     * Reads recent Messages using the hosted service's default limit of 50.
     * The default HTTP adapter sends no explicit limit.
     * Returns an immutable snapshot in the service's newest-first order.
     *
     * <p>Preserves repeated Messages and performs no local sorting, reversal,
     * deduplication, pagination, or remote-history trimming. An explicit
     * empty result succeeds; a missing or null required messages array in
     * an HTTP response fails the future with {@link ResponseDecodingException}.
     *
     * @param conversationId the Conversation to read
     * @return a future containing the recent Messages
     */
    CompletableFuture<List<Message>> messages(UUID conversationId);

    /**
     * Requests up to {@code limit} recent Messages from the Conversation.
     * Returns an immutable snapshot in the service's newest-first order.
     * Preserves the returned sequence without sorting, reversing,
     * deduplicating, paginating, or locally truncating it.
     * Reading does not trim remote history.
     *
     * <p>An explicit empty result succeeds; a missing or null required
     * messages array in an HTTP response fails the future with
     * {@link ResponseDecodingException}.
     *
     * @param conversationId the Conversation to read
     * @param limit the requested maximum number of Messages, from 1 through 200
     * @return a future containing the recent Messages
     * @throws IllegalArgumentException if limit is outside 1–200;
     *         thrown before this method returns a future or sends a request
     */
    CompletableFuture<List<Message>> messages(UUID conversationId, int limit);

    /**
     * Fetches Conversation Context containing reflections, observations,
     * and recent Messages. All three collections are immutable and present;
     * absent optional collections become empty lists.
     *
     * @param conversationId the Conversation whose context is required
     * @return a future containing a fresh Conversation Context snapshot
     */
    CompletableFuture<ConversationContext> context(UUID conversationId);

    /**
     * Persists a Reasoning Step in the identified Conversation.
     * Reasoning, action, and optional result are supplied by the application;
     * the client does not obtain reasoning from a model or execute the action.
     *
     * @param conversationId the Conversation that owns the Reasoning Step
     * @param step the reasoning, action, and optional result to record
     * @return a future containing a live Reasoning Step bound to this client
     */
    CompletableFuture<ReasoningStep> recordStep(
            UUID conversationId, NewReasoningStep step);

    /**
     * Records a Tool Call belonging to the identified Reasoning Step.
     * Input and optional output are opaque strings; they are not parsed
     * as JSON. Recording the Tool Call does not execute the tool.
     *
     * @param stepId the Reasoning Step that owns the Tool Call
     * @param call the tool name, payloads, status, and optional duration
     * @return a future containing the recorded Tool Call snapshot
     */
    CompletableFuture<ToolCall> recordToolCall(UUID stepId, NewToolCall call);

    /**
     * Fetches the Reasoning Trace for a Conversation.
     * Step and Tool Call collections are immutable snapshots in service
     * order. Every included Reasoning Step remains a live handle bound
     * to this client.
     *
     * @param conversationId the Conversation whose trace is required
     * @return a future containing the Reasoning Trace
     */
    CompletableFuture<ReasoningTrace> trace(UUID conversationId);

    /**
     * Fetches a Reasoning Step Explanation containing the step, its recorded
     * Tool Calls, and influenced Entities. Returned collections are immutable
     * snapshots, and the included Reasoning Step is bound to this client.
     *
     * @param stepId the Reasoning Step to explain
     * @return a future containing the Reasoning Step Explanation
     */
    CompletableFuture<ReasoningStepExplanation> explainReasoningStep(UUID stepId);

    /**
     * Deletes the identified Conversation.
     * The client does not retry the write automatically.
     *
     * @param conversationId the Conversation to delete
     * @return a future that completes with null when deletion succeeds
     */
    CompletableFuture<Void> deleteConversation(UUID conversationId);
}
