package org.neo4j.agentmemory.conformance;

import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.conversation.*;
import org.neo4j.agentmemory.entity.*;
import org.neo4j.agentmemory.reasoning.*;
import org.neo4j.agentmemory.user.UserMemoryDeletion;

import java.util.*;
import java.util.concurrent.CompletableFuture;

final class FakeMemoryClient implements MemoryClient {
    final List<Conversation> conversations = new ArrayList<>();
    final List<CreateConversation> creates = new ArrayList<>();
    final List<ListConversations> lists = new ArrayList<>();
    final List<String> deletedUsers = new ArrayList<>();
    final Deque<Boolean> deletions = new ArrayDeque<>();

    @Override
    public CompletableFuture<Conversation> createConversation(CreateConversation request) {
        creates.add(request);
        var conversation = new Conversation(this, UUID.randomUUID(), request.userId(),
                request.metadata(), null, null, null, null, null);
        conversations.add(conversation);
        return CompletableFuture.completedFuture(conversation);
    }

    @Override
    public CompletableFuture<List<Conversation>> listConversations(ListConversations request) {
        lists.add(request);
        return CompletableFuture.completedFuture(conversations.stream()
                .filter(c -> request.getUserId().isEmpty() || c.userId().equals(request.getUserId()))
                .limit(request.limit()).toList());
    }

    @Override
    public CompletableFuture<Void> deleteConversation(UUID conversationId) {
        conversations.removeIf(c -> c.id().equals(conversationId));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<UserMemoryDeletion> deleteUserMemory(String userId) {
        deletedUsers.add(userId);
        boolean complete = deletions.isEmpty() || deletions.removeFirst();
        if (complete) {
            conversations.removeIf(c -> c.userId().equals(Optional.of(userId)));
        }
        return CompletableFuture.completedFuture(new UserMemoryDeletion(userId, complete));
    }

    @Override
    public CompletableFuture<Conversation> getConversation(UUID conversationId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<List<Entity>> searchEntities(EntitySearch search) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Message> addMessage(UUID conversationId, NewMessage message) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<List<Message>> addMessages(UUID conversationId, List<NewMessage> messages) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<List<Message>> messages(UUID conversationId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<List<Message>> messages(UUID conversationId, int limit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<ConversationContext> context(UUID conversationId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<ReasoningStep> recordStep(UUID conversationId, NewReasoningStep step) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<ToolCall> recordToolCall(UUID stepId, NewToolCall call) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<ReasoningTrace> trace(UUID conversationId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<ReasoningStepExplanation> explainReasoningStep(UUID stepId) {
        throw new UnsupportedOperationException();
    }
}
