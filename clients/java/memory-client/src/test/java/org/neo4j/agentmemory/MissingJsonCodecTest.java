package org.neo4j.agentmemory;

import io.github.netmikey.logunit.api.LogCapturer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.neo4j.agentmemory.exception.MissingJsonCodecException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@Tag("without-json-codecs")
class MissingJsonCodecTest {
    @RegisterExtension
    final LogCapturer logs = LogCapturer.create().captureForType(MemoryClient.class);

    @Test
    void neitherMajorFailsWithBothCoordinates() {
        for (var className : List.of(
                "tools.jackson.databind.json.JsonMapper", "tools.jackson.core.JsonFactory",
                "com.fasterxml.jackson.databind.json.JsonMapper", "com.fasterxml.jackson.core.JsonFactory")) {
            assertThatThrownBy(() -> Class.forName(className))
                    .isInstanceOf(ClassNotFoundException.class);
        }
        var configuration = MemoryClientConfiguration.builder()
                .apiKey("workspace-key").baseUrl("https://memory.test/v1").build();

        var failure = catchThrowable(() -> MemoryClient.create(configuration));

        assertThat(failure).isInstanceOf(MissingJsonCodecException.class)
                .hasMessage("No supported JSON codec is available; add tools.jackson.core:jackson-databind 3.1.4+ "
                        + "or com.fasterxml.jackson.core:jackson-databind 2.19.0+");
        assertThat(failure.getSuppressed()).hasSize(2)
                .allSatisfy(cause -> assertThat(cause).isInstanceOf(ClassNotFoundException.class));
        assertThat(logs.getEvents()).isEmpty();
    }
}
