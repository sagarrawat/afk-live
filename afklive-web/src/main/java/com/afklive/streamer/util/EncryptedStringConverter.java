package com.afklive.streamer.util;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** JPA column converter: secrets are encrypted in the database and plain in the application. */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return SecretBox.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return SecretBox.decrypt(dbData);
    }
}
