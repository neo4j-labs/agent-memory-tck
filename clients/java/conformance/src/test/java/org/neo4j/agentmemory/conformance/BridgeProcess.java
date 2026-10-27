package org.neo4j.agentmemory.conformance;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static java.util.concurrent.TimeUnit.SECONDS;

/** Runs the public bridge against a test-owned service endpoint. */
final class BridgeProcess implements AutoCloseable {
    private final HttpClient http = HttpClient.newHttpClient();
    private final URI uri;
    private final Process process;

    BridgeProcess(String endpoint, Path log) throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        uri = URI.create("http://127.0.0.1:" + port);
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), ConformanceServer.class.getName());
        builder.environment().put("MEMORY_API_KEY", "test-key");
        builder.environment().put("MEMORY_ENDPOINT", endpoint);
        builder.environment().put("TCK_BRIDGE_PORT", Integer.toString(port));
        process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            var deadline = System.nanoTime() + SECONDS.toNanos(10);
            while (process.isAlive() && System.nanoTime() < deadline) {
                try {
                    if (call("setup", "{}").statusCode() == 200) return;
                } catch (IOException starting) {
                    Thread.sleep(25);
                }
            }
            throw new AssertionError("Bridge did not start: " + Files.readString(log));
        } catch (Exception | AssertionError failure) {
            close();
            throw failure;
        }
    }

    HttpResponse<String> call(String method, String body) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(uri.resolve("/" + method))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public void close() throws InterruptedException {
        process.destroyForcibly();
        if (!process.waitFor(5, SECONDS)) throw new AssertionError("Bridge did not stop");
    }
}
