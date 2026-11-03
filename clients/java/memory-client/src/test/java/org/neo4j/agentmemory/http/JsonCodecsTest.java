package org.neo4j.agentmemory.http;

import org.junit.jupiter.api.Test;
import org.neo4j.agentmemory.exception.MissingJsonCodecException;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonCodecsTest {
    @Test
    void selectsTheFirstUsableCandidate() {
        assertThat(JsonCodecs.select(List.of(usable("first"), usable("second"))).name())
                .isEqualTo("first");
    }

    @Test
    void skipsCandidateWhoseMapperClassIsAbsent() {
        var absent = new JsonCodecs.Candidate("a:x 1+", "org.example.Absent", () -> stub("first"));

        assertThat(JsonCodecs.select(List.of(absent, usable("second"))).name()).isEqualTo("second");
    }

    @Test
    void skipsCandidateWhoseCodecFailsToLink() {
        var broken = new JsonCodecs.Candidate("a:x 1+", "java.lang.Object", () -> {
            throw new NoSuchFieldError("WRITE_ENUMS_TO_LOWERCASE");
        });

        assertThat(JsonCodecs.select(List.of(broken, usable("second"))).name()).isEqualTo("second");
    }

    @Test
    void reportsEveryCoordinateAndFailureWhenNoneIsUsable() {
        var absent = new JsonCodecs.Candidate("a:x 1+", "org.example.Absent", () -> stub("first"));
        var linkageFailure = new NoClassDefFoundError("broken mapper");
        var broken = new JsonCodecs.Candidate("b:y 2+", "java.lang.Object", () -> {
            throw linkageFailure;
        });

        var failure = assertThrows(MissingJsonCodecException.class,
                () -> JsonCodecs.select(List.of(absent, broken)));

        assertThat(failure).hasMessage("No supported JSON codec is available; add a:x 1+ or b:y 2+")
                .hasNoCause();
        assertThat(failure.getSuppressed()).hasSize(2);
        assertThat(failure.getSuppressed()[0]).isInstanceOf(ClassNotFoundException.class);
        assertThat(failure.getSuppressed()[1]).isSameAs(linkageFailure);
    }

    @Test
    void propagatesUnexpectedFailures() {
        var unexpected = new IllegalStateException("unexpected");
        var triedSecond = new AtomicBoolean();
        var first = new JsonCodecs.Candidate("a:x 1+", "java.lang.Object", () -> {
            throw unexpected;
        });
        var second = new JsonCodecs.Candidate("b:y 2+", "java.lang.Object", () -> {
            triedSecond.set(true);
            return stub("second");
        });

        assertThat(assertThrows(IllegalStateException.class,
                () -> JsonCodecs.select(List.of(first, second)))).isSameAs(unexpected);
        assertThat(triedSecond).isFalse();
    }

    @Test
    void prefersJackson3WhenBothArePresent() {
        assertThat(JsonCodecs.select().name()).isEqualTo("jackson3");
    }

    private static JsonCodecs.Candidate usable(String name) {
        return new JsonCodecs.Candidate(name, "java.lang.Object", () -> stub(name));
    }

    private static JsonCodec stub(String name) {
        return new JsonCodec() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public byte[] encode(Object wireValue) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T decode(byte[] json, Class<T> wireType) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
