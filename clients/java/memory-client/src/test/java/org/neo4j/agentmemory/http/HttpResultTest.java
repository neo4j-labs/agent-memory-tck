package org.neo4j.agentmemory.http;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpResultTest {
    @Test void headerLookupIgnoresNameCase() {
        var result = new HttpResult(503, Map.of("X-Request-ID", List.of("req-1")), null);
        assertThat(result.firstHeader("x-request-id")).contains("req-1");
        assertThat(result.headers().get("x-REQUEST-id")).containsExactly("req-1");
        assertThat(result.body()).isEmpty();
    }

    @Test void headersAreImmutable() {
        var result = new HttpResult(200, Map.of("A", List.of("1")), new byte[0]);
        assertThatThrownBy(() -> result.headers().put("B", List.of())).isInstanceOf(UnsupportedOperationException.class);
    }
}
