package org.neo4j.agentmemory.http;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.cfg.EnumFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.io.IOException;

final class Jackson2JsonCodec implements JsonCodec {
    private final JsonMapper mapper;

    Jackson2JsonCodec() {
        // Jackson 3 defaults that Jackson 2 does not share, pinned for parity.
        var factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(500)
                        .maxStringLength(100_000_000)
                        .build())
                .build();
        this.mapper = JsonMapper.builder(factory)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(MapperFeature.SORT_CREATOR_PROPERTIES_BY_DECLARATION_ORDER)
                // Settings shared with Jackson3JsonCodec.
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(EnumFeature.WRITE_ENUMS_TO_LOWERCASE)
                .withCoercionConfig(LogicalType.Textual, coercion -> coercion
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                .build();
    }

    @Override
    public String name() {
        return "jackson2";
    }

    @Override
    public byte[] encode(Object wireValue) {
        try {
            return mapper.writeValueAsBytes(wireValue);
        } catch (JsonProcessingException failure) {
            throw new JsonCodecException("Could not encode JSON", failure);
        }
    }

    @Override
    public <T> T decode(byte[] json, Class<T> wireType) {
        try {
            return mapper.readValue(json, wireType);
        } catch (IOException failure) {
            throw new JsonCodecException("Could not decode JSON", failure);
        }
    }
}
