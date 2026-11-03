package org.neo4j.agentmemory.http;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

final class Jackson3JsonCodec implements JsonCodec {
    private final JsonMapper mapper;

    Jackson3JsonCodec() {
        this.mapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(inclusion ->
                        inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(EnumFeature.WRITE_ENUMS_TO_LOWERCASE)
                .withCoercionConfig(LogicalType.Textual, coercion -> coercion
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                .build();
    }

    @Override
    public String name() {
        return "jackson3";
    }

    @Override
    public byte[] encode(Object wireValue) {
        try {
            return mapper.writeValueAsBytes(wireValue);
        } catch (JacksonException failure) {
            throw new JsonCodecException("Could not encode JSON", failure);
        }
    }

    @Override
    public <T> T decode(byte[] json, Class<T> wireType) {
        try {
            return mapper.readValue(json, wireType);
        } catch (JacksonException failure) {
            throw new JsonCodecException("Could not decode JSON", failure);
        }
    }
}
