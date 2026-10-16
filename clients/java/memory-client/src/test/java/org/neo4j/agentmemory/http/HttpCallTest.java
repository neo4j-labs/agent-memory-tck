package org.neo4j.agentmemory.http;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class HttpCallTest {
    @Test void expandsVariablesIntoAnAbsoluteUri() {
        var id = UUID.fromString("a2f55d70-838f-4c41-ae7d-dad30fd25720");
        var call = HttpCall.get("https://memory.test/v1/conversations/{conversationId}/messages?limit={limit}",
                Map.of("conversationId", id, "limit", 20), Map.of());
        assertThat(call.uri()).isEqualTo(URI.create("https://memory.test/v1/conversations/" + id + "/messages?limit=20"));
    }

    @Test void stringVariablesAreStrictlyPercentEncoded() {
        var call = HttpCall.get("https://example.test/v1/conversations?limit={limit}&userId={userId}",
                Map.of("limit", 25, "userId", "user one&a+b/ł"), Map.of());
        assertThat(call.uri().toASCIIString()).isEqualTo(
                "https://example.test/v1/conversations?limit=25&userId=user%20one%26a%2Bb%2F%C5%82");
    }

    @Test void stringVariablesPreserveUnreservedCharactersAndEncodeReplacementSyntax() {
        var call = HttpCall.get("https://example.test/{value}",
                Map.of("value", "AZaz09-._~$\\"), Map.of());
        assertThat(call.uri().toASCIIString()).isEqualTo("https://example.test/AZaz09-._~%24%5C");
    }

    @Test void rejectsUnsupportedVariableTypes() {
        assertThatIllegalArgumentException().isThrownBy(() -> HttpCall.get(
                "https://memory.test/v1/x/{q}", Map.of("q", 25L), Map.of()));
    }

    @Test void rejectsUnboundTemplateVariables() {
        var call = HttpCall.get("https://memory.test/v1/conversations/{conversationId}", Map.of(), Map.of());
        assertThatIllegalArgumentException().isThrownBy(call::uri);
    }
}
