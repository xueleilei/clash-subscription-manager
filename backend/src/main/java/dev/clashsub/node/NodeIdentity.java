package dev.clashsub.node;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public final class NodeIdentity {
    private NodeIdentity() { }
    public static NodeKey key(UUID sourceId, String exactName) {
        Objects.requireNonNull(sourceId);
        Objects.requireNonNull(exactName);
        try {
            var bytes = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(exactName));
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes);
            return new NodeKey(sourceId, HexFormat.of().formatHex(digest.digest()), exactName);
        } catch (CharacterCodingException exception) { throw new IllegalArgumentException("Node name must contain valid Unicode"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
