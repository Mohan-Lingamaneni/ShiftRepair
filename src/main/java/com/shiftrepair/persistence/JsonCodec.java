package com.shiftrepair.persistence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.shiftrepair.model.Qualification;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Typed immutable policy/history/score values only. No polymorphic class-name deserialization. */
final class JsonCodec {
    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).addModule(new Jdk8Module())
            .addModule(keys()).disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private static SimpleModule keys() {
        var module = new SimpleModule();
        module.addKeySerializer(Qualification.class, new StdSerializer<Qualification>(Qualification.class) {
            @Override public void serialize(Qualification value, com.fasterxml.jackson.core.JsonGenerator generator,
                                            com.fasterxml.jackson.databind.SerializerProvider provider) throws java.io.IOException {
                generator.writeFieldName(value.type().name() + "|" + value.code());
            }
        });
        module.addKeyDeserializer(Qualification.class, new KeyDeserializer() {
            @Override public Object deserializeKey(String key, com.fasterxml.jackson.databind.DeserializationContext context) {
                int separator = key.indexOf('|');
                return new Qualification(Qualification.Type.valueOf(key.substring(0, separator)), key.substring(separator + 1));
            }
        });
        return module;
    }

    String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new PersistenceException("Cannot encode stored value", error); }
    }

    <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (Exception error) { throw new PersistenceException("Invalid stored " + type.getSimpleName(), error); }
    }
}
