package dev.clashsub.parse;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class Base64SubscriptionDecoder {
    private Base64SubscriptionDecoder() { }
    public static String decode(String value) {
        String encoded = value.replaceAll("[\\r\\n\\t ]", "");
        if (encoded.isEmpty() || encoded.length() > SafeYaml.MAX_BYTES
                || !encoded.matches("[A-Za-z0-9+/_-]+={0,2}")) { throw new IllegalArgumentException("Invalid Base64"); }
        try {
            String normalized = encoded.replace('-', '+').replace('_', '/');
            byte[] bytes = Base64.getDecoder().decode(normalized);
            if (!Base64.getEncoder().withoutPadding().encodeToString(bytes).equals(normalized.replace("=", ""))) {
                throw new IllegalArgumentException("Non-canonical Base64");
            }
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException exception) { throw new IllegalArgumentException("Invalid UTF-8"); }
    }
}
