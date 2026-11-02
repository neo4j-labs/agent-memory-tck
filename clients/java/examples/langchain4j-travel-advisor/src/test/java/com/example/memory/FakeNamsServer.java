package com.example.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local workspace shared by startup and native HTTP tests. */
public final class FakeNamsServer implements AutoCloseable {
    public static final String ID = "1a637019-530f-4a3c-911d-4bd34e2d7b8c";
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Map<String, Object>> conversations = new HashMap<>();
    private final Map<String, List<Map<String, Object>>> messagesByConversation = new HashMap<>();
    public final List<Map<String, Object>> messages = new CopyOnWriteArrayList<>();
    /** Additional service-selected excerpts, alongside the latest persisted messages. */
    public final List<Map<String, Object>> contextMessages = new CopyOnWriteArrayList<>();
    public final List<Map<String, Object>> observations = new CopyOnWriteArrayList<>();
    public final List<Map<String, Object>> reflections = new CopyOnWriteArrayList<>();
    public final List<Map<String, Object>> steps = new CopyOnWriteArrayList<>();
    public final List<Map<String, Object>> toolCalls = new CopyOnWriteArrayList<>();
    public volatile int status = 200;
    public volatile long delay;
    public volatile long contextDelay;
    public volatile int appendStatus = 200;
    public volatile int deleteStatus = 200;
    public volatile int contextStatus = 200;
    public volatile int stepStatus = 200;
    public volatile int toolStatus = 200;
    public volatile int failToolWriteNumber;
    public volatile int commitThenFailToolWriteNumber;
    public volatile String contextBodyOverride;
    public int creates;
    public int appends;
    public int deletes;
    public int contextReads;
    public int messageReads;
    public int stepWrites;
    public int toolWrites;
    public int traceReads;

    public synchronized void seedEmptyConversation(String id) {
        conversations.put(id, Map.of("id", id, "metadata", Map.of("title", "Untouched empty conversation")));
        messagesByConversation.put(id, new ArrayList<>());
    }

    public FakeNamsServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(executor);
        server.createContext("/v1", exchange -> {
            try {
                Thread.sleep(delay);
                String path = exchange.getRequestURI().getPath();
                long responseDelay = path.endsWith("/context") ? contextDelay : 0;
                String method = exchange.getRequestMethod();
                Object response = Map.of();
                String literalBody = null;
                int code = status;
                synchronized (this) {
                    if (path.startsWith("/v1/reasoning/trace/")) {
                        traceReads++;
                        code = 500;
                    } else if (path.equals("/v1/reasoning/steps")) {
                        stepWrites++;
                        if (code == 200) code = stepStatus;
                        var step = json.readValue(exchange.getRequestBody(), new TypeReference<Map<String, Object>>() {});
                        if (code == 200 && !conversations.containsKey(step.get("conversationId"))) code = 404;
                        step.put("id", UUID.randomUUID().toString());
                        if (code == 200) steps.add(step);
                        response = step;
                    } else if (path.equals("/v1/reasoning/tool-calls")) {
                        toolWrites++;
                        if (code == 200) code = toolStatus;
                        if (code == 200 && toolWrites == failToolWriteNumber) code = 503;
                        var call = json.readValue(exchange.getRequestBody(), new TypeReference<Map<String, Object>>() {});
                        if (code == 200 && steps.stream().noneMatch(step -> step.get("id").equals(call.get("stepId")))) code = 404;
                        call.put("id", UUID.randomUUID().toString());
                        if (code == 200) {
                            toolCalls.add(call);
                            if (toolWrites == commitThenFailToolWriteNumber) code = 503;
                        }
                        response = call;
                    } else if (path.endsWith("/context")) {
                        contextReads++;
                        if (code == 200) code = contextStatus;
                        String id = path.substring("/v1/conversations/".length(), path.length() - "/context".length());
                        if (code == 200 && !conversations.containsKey(id)) code = 404;
                        var recent = new ArrayList<>(messagesByConversation.getOrDefault(id, List.of()));
                        Collections.reverse(recent);
                        recent = new ArrayList<>(recent.subList(0, Math.min(recent.size(), 20)));
                        if (id.equals(ID)) recent.addAll(contextMessages);
                        response = id.equals(ID)
                            ? Map.of("reflections", reflections, "observations", observations, "recentMessages", recent)
                            : Map.of("reflections", List.of(), "observations", List.of(), "recentMessages", recent);
                        literalBody = contextBodyOverride;
                    } else if (path.endsWith("/messages/bulk") && method.equals("POST")) {
                        String id = path.substring("/v1/conversations/".length(), path.length() - "/messages/bulk".length());
                        if (code == 200 && !conversations.containsKey(id)) code = 404;
                        if (code == 200) code = appendStatus;
                        var batch = json.readValue(exchange.getRequestBody(),
                            new TypeReference<Map<String, List<Map<String, Object>>>>() {}).get("messages");
                        appends += batch.size();
                        for (var message : batch) {
                            message.put("id", UUID.randomUUID().toString());
                        }
                        if (code == 200) {
                            messagesByConversation.get(id).addAll(batch);
                            if (id.equals(ID)) messages.addAll(batch);
                        }
                        response = Map.of("messages", batch);
                    } else if (path.endsWith("/messages")) {
                        String id = path.substring("/v1/conversations/".length(), path.length() - "/messages".length());
                        if (code == 200 && !conversations.containsKey(id)) code = 404;
                        if (method.equals("POST")) {
                            appends++;
                            if (code == 200) code = appendStatus;
                            var message = json.readValue(exchange.getRequestBody(), new TypeReference<Map<String, Object>>() {});
                            message.put("id", UUID.randomUUID().toString());
                            if (code == 200) {
                                messagesByConversation.get(id).add(message);
                                if (id.equals(ID)) messages.add(message);
                            }
                            response = message;
                        } else {
                            messageReads++;
                            var recent = new ArrayList<>(messagesByConversation.getOrDefault(id, List.of()));
                            Collections.reverse(recent);
                            String limit = exchange.getRequestURI().getRawQuery();
                            if (limit != null && limit.startsWith("limit=")) {
                                recent = new ArrayList<>(recent.subList(0, Math.min(recent.size(), Integer.parseInt(limit.substring(6)))));
                            }
                            response = Map.of("messages", recent);
                        }
                    } else if (path.equals("/v1/conversations")) {
                        if (method.equals("POST")) {
                            creates++;
                            var body = json.readValue(exchange.getRequestBody(), new TypeReference<Map<String, Object>>() {});
                            response = Map.of("id", ID, "metadata", body.get("metadata"));
                            if (code == 200) {
                                conversations.put(ID, Map.of("id", ID, "metadata", body.get("metadata")));
                                messagesByConversation.put(ID, new ArrayList<>());
                                messages.clear();
                                removeActivity(ID);
                            }
                        } else {
                            response = Map.of("conversations", new ArrayList<>(conversations.values()));
                        }
                    } else {
                        String id = path.substring("/v1/conversations/".length());
                        if (code == 200 && !conversations.containsKey(id)) code = 404;
                        response = conversations.getOrDefault(id, Map.of());
                        if (method.equals("DELETE")) {
                            deletes++;
                            if (code == 200) code = deleteStatus;
                            if (code == 200) {
                                conversations.remove(id);
                                messagesByConversation.remove(id);
                                if (id.equals(ID)) messages.clear();
                                removeActivity(id);
                                code = 204;
                            }
                        }
                    }
                }
                Thread.sleep(responseDelay);
                byte[] bytes = literalBody == null ? json.writeValueAsBytes(response) : literalBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(code, code == 204 ? -1 : bytes.length);
                if (code != 204) exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private void removeActivity(String id) {
        var stepIds = steps.stream().filter(step -> step.get("conversationId").equals(id)).map(step -> step.get("id")).toList();
        toolCalls.removeIf(call -> stepIds.contains(call.get("stepId")));
        steps.removeIf(step -> step.get("conversationId").equals(id));
        if (id.equals(ID)) {
            contextMessages.clear();
            observations.clear();
            reflections.clear();
        }
    }

    public String url() { return "http://localhost:" + server.getAddress().getPort() + "/v1"; }
    public void close() { server.stop(0); executor.shutdownNow(); }
}
