package dev.clashsub.common;

import dev.clashsub.profile.ProfileDocument;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class DefinitionHasher {
    private final JsonMapper json;
    public DefinitionHasher(JsonMapper json) { this.json = json; }
    public String hash(ProfileDocument document) { return sha256(json.writeValueAsBytes(canonical(json.valueToTree(document)))); }
    public static String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }
    public static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }
    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) { var result = json.createObjectNode(); node.properties().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> result.set(entry.getKey(), canonical(entry.getValue()))); return result; }
        if (node.isArray()) { var result = json.createArrayNode(); node.forEach(value -> result.add(canonical(value))); return result; }
        return node;
    }
}
