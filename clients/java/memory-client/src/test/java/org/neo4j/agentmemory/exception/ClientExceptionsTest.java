package org.neo4j.agentmemory.exception;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientExceptionsTest {
    @Test
    void serviceFailureRetainsDiagnosticsAndDeepCopiesHeaders() {
        var headerValues = new ArrayList<>(List.of("request-1"));
        var headers = new LinkedHashMap<String, List<String>>();
        headers.put("x-request-id", headerValues);

        var failure = new MemoryServiceException(
                "createConversation", 503, headers, "application/json", "{\"error\":\"unavailable\"}");

        headerValues.add("request-2");
        headers.clear();

        assertThat(failure).isInstanceOf(MemoryClientException.class);
        assertThat(failure.operation()).isEqualTo("createConversation");
        assertThat(failure.statusCode()).isEqualTo(503);
        assertThat(failure.responseHeaders()).containsEntry("x-request-id", List.of("request-1"));
        assertThat(failure.contentType()).isEqualTo("application/json");
        assertThat(failure.responseBodyExcerpt()).isEqualTo("{\"error\":\"unavailable\"}");
        assertThatThrownBy(() -> failure.responseHeaders().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> failure.responseHeaders().get("x-request-id").clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void decodingFailureRetainsResponseContextAndCause() {
        var cause = new IllegalArgumentException("bad UUID");

        var failure = new ResponseDecodingException(
                "getConversation", 200, "application/json", "{\"id\":\"not-a-uuid\"}", cause);

        assertThat(failure).isInstanceOf(MemoryClientException.class);
        assertThat(failure.operation()).isEqualTo("getConversation");
        assertThat(failure.statusCode()).isEqualTo(200);
        assertThat(failure.contentType()).isEqualTo("application/json");
        assertThat(failure.responseBodyExcerpt()).isEqualTo("{\"id\":\"not-a-uuid\"}");
        assertThat(failure).hasCause(cause);
    }

    @Test
    void missingCodecFailureBelongsToClientHierarchy() {
        assertThat(new MissingJsonCodecException("missing", new ClassNotFoundException()))
                .isInstanceOf(MemoryClientException.class);
    }
}
