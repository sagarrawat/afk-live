package com.afklive.streamer.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

/**
 * OPS-4: sessions live in PostgreSQL (Spring Session JDBC), so both web slots share every sign-in.
 * <p>
 * Session attributes are stored with Java serialization. A release that upgrades Spring Security can
 * change a class's serialized form, and then the new slot can't read sessions the old one wrote; by
 * default every such request would fail with an error. Here an attribute that can't be read counts as
 * absent instead: that visitor is signed out once (remember-me signs them back in) and the page works.
 */
@Configuration
@Slf4j
public class SessionConfig {

    @Bean
    public SessionRepositoryCustomizer<JdbcIndexedSessionRepository> tolerantSessionAttributes() {
        return repository -> repository.setConversionService(tolerantConversionService(SessionConfig.class.getClassLoader()));
    }

    static GenericConversionService tolerantConversionService(ClassLoader classLoader) {
        GenericConversionService service = new GenericConversionService();
        service.addConverter(Object.class, byte[].class, new SerializingConverter());
        DeserializingConverter deserializer = new DeserializingConverter(classLoader);
        Converter<byte[], Object> tolerant = bytes -> {
            try {
                return deserializer.convert(bytes);
            } catch (RuntimeException e) {
                log.info("A stored session attribute couldn't be read (written by another version?); treating it as absent: {}",
                        e.getMessage());
                return null;
            }
        };
        service.addConverter(byte[].class, Object.class, tolerant);
        return service;
    }
}
