package org.neo4j.agentmemory.conformance;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.exception.MemoryServiceException;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.IdentityHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

public final class ConformanceServer {
    private static final System.Logger LOGGER = System.getLogger("org.neo4j.agentmemory.conformance");

    private ConformanceServer() {}

    public static void main(String[] args) throws IOException {
        var client = MemoryClient.create();
        var port = Integer.parseInt(System.getenv().getOrDefault("TCK_BRIDGE_PORT", "3001"));
        var json = new BridgeJson();
        var methods = new BridgeMethods(client, json);
        var server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", exchange -> handle(exchange, methods, json));
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        System.out.println("Java conformance server running on http://localhost:" + port);
    }

    private static void handle(HttpExchange exchange, BridgeMethods methods, BridgeJson json) throws IOException {
        try (exchange) {
            if (!exchange.getRequestMethod().equals("POST")) {
                respond(exchange, 405, null, json);
                return;
            }
            var name = exchange.getRequestURI().getPath().substring(1);
            var method = methods.find(name);
            if (method == null) {
                respond(exchange, 404, json.object().put("error", "Unknown method: " + name), json);
                return;
            }
            int status;
            JsonNode response;
            try {
                byte[] bytes;
                try { bytes = exchange.getRequestBody().readAllBytes(); }
                catch (IOException unreadable) { throw new BridgeJson.InvalidArgument("unreadable body"); }
                response = method.invoke(json.read(bytes));
                status = response == null ? 204 : 200;
            } catch (BridgeJson.InvalidArgument invalid) {
                status = 400;
                response = json.object().put("error", name + ": " + invalid.getMessage());
            } catch (BridgeMethods.Gap gap) {
                status = 501;
                response = json.object().put("error", gap.getMessage());
            } catch (Exception failure) {
                status = 500;
                String reason;
                if (failure instanceof MemoryServiceException service) {
                    reason = "HTTP " + service.statusCode() + json.serviceCode(service.responseBodyExcerpt());
                } else if (hasTimeout(failure)) {
                    reason = "timed out after 20s";
                } else {
                    reason = failure.getClass().getSimpleName();
                }
                response = json.object().put("error", name + " failed: " + reason);
                LOGGER.log(System.Logger.Level.WARNING, "Bridge method failed",
                        redacted(failure, new IdentityHashMap<>()));
            }
            respond(exchange, status, response, json);
        }
    }

    private static boolean hasTimeout(Throwable failure) {
        var seen = new IdentityHashMap<Throwable, Boolean>();
        while (failure != null && seen.put(failure, true) == null) {
            if (failure instanceof TimeoutException) return true;
            failure = failure.getCause();
        }
        return false;
    }

    // Copy the diagnostic graph, retaining types and frames without arbitrary messages.
    private static Throwable redacted(Throwable failure, IdentityHashMap<Throwable, Throwable> seen) {
        if (seen.containsKey(failure)) return seen.get(failure);
        var copy = new Throwable() {
            @Override public String toString() { return failure.getClass().getName() + ": [redacted]"; }
        };
        seen.put(failure, copy);
        copy.setStackTrace(failure.getStackTrace());
        if (failure.getCause() != null) copy.initCause(redacted(failure.getCause(), seen));
        for (var suppressed : failure.getSuppressed()) copy.addSuppressed(redacted(suppressed, seen));
        return copy;
    }

    private static void respond(HttpExchange exchange, int status, JsonNode body, BridgeJson json)
            throws IOException {
        if (body == null) { exchange.sendResponseHeaders(status, -1); return; }
        var bytes = json.write(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
