package dev.clashsub.common;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class EncryptedDocuments {
    private final SecretCipher cipher;
    private final JsonMapper json;

    public EncryptedDocuments(SecretCipher cipher, JsonMapper json) { this.cipher = cipher; this.json = json; }
    public String write(Object value, String context) {
        return json.writeValueAsString(cipher.encrypt(json.writeValueAsBytes(value), context));
    }
    public <T> T read(String value, String context, Class<T> type) {
        try { return json.readValue(cipher.decrypt(json.readValue(value, EncryptedValue.class), context), type); }
        catch (RuntimeException exception) { throw new SecretStorageException(); }
    }
}
