package dev.clashsub.common;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class SecretCipher {
    private static final int VERSION = 1;
    private final SecretKeySpec key;
    private final String keyId;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(byte[] bytes) {
        if (bytes == null || bytes.length != 32) { throw new SecretStorageException(); }
        this.key = new SecretKeySpec(bytes.clone(), "AES");
        try { this.keyId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16); }
        catch (GeneralSecurityException exception) { throw new SecretStorageException(); }
    }

    public static SecretCipher fromFile(Path path) {
        try (var input = Files.newInputStream(path)) {
            var bytes = input.readNBytes(33);
            try { return new SecretCipher(bytes); } finally { Arrays.fill(bytes, (byte) 0); }
        } catch (java.io.IOException exception) { throw new SecretStorageException(); }
    }

    public EncryptedValue encrypt(byte[] plaintext, String context) {
        try {
            var nonce = new byte[12];
            random.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(context));
            return new EncryptedValue(VERSION, keyId, Base64.getEncoder().encodeToString(nonce),
                    Base64.getEncoder().encodeToString(cipher.doFinal(plaintext)));
        } catch (GeneralSecurityException | IllegalArgumentException exception) { throw new SecretStorageException(); }
    }

    public byte[] decrypt(EncryptedValue value, String context) {
        try {
            if (value.version() != VERSION || !keyId.equals(value.keyId())) { throw new SecretStorageException(); }
            var nonce = Base64.getDecoder().decode(value.nonce());
            if (nonce.length != 12) { throw new SecretStorageException(); }
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(context));
            return cipher.doFinal(Base64.getDecoder().decode(value.ciphertext()));
        } catch (GeneralSecurityException | IllegalArgumentException exception) { throw new SecretStorageException(); }
    }

    private byte[] aad(String context) {
        if (context == null || context.isBlank()) { throw new SecretStorageException(); }
        return (VERSION + ":" + keyId + ":" + context).getBytes(StandardCharsets.UTF_8);
    }
}
