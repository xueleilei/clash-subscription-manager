package dev.clashsub.common;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {
    @TempDir Path temp;
    private final byte[] secret = "credential-fixture-only".getBytes(StandardCharsets.UTF_8);

    @Test void usesRandomNonceAndAuthenticatedContext() {
        var cipher = new SecretCipher(key());
        var first = cipher.encrypt(secret, "node:1");
        var second = cipher.encrypt(secret, "node:1");
        assertThat(first).isNotEqualTo(second);
        assertThat(Base64.getDecoder().decode(first.nonce())).hasSize(12);
        assertThat(cipher.decrypt(first, "node:1")).isEqualTo(secret);
        assertThat(first.toString()).doesNotContain("credential", first.ciphertext());
        assertThatThrownBy(() -> cipher.decrypt(first, "node:2")).isInstanceOf(SecretStorageException.class);
    }

    @Test void rejectsTamperingAndWrongKey() {
        var cipher = new SecretCipher(key());
        var encrypted = cipher.encrypt(secret, "source:1");
        var bytes = Base64.getDecoder().decode(encrypted.ciphertext());
        bytes[0] ^= 1;
        var changed = new EncryptedValue(encrypted.version(), encrypted.keyId(), encrypted.nonce(), Base64.getEncoder().encodeToString(bytes));
        assertThatThrownBy(() -> cipher.decrypt(changed, "source:1")).isInstanceOf(SecretStorageException.class);
        assertThatThrownBy(() -> new SecretCipher(key()).decrypt(encrypted, "source:1")).isInstanceOf(SecretStorageException.class);
        var wrongVersion = new EncryptedValue(99, encrypted.keyId(), encrypted.nonce(), encrypted.ciphertext());
        assertThatThrownBy(() -> cipher.decrypt(wrongVersion, "source:1")).isInstanceOf(SecretStorageException.class);
    }

    @Test void requiresExactPersistentKeyFile() throws Exception {
        var path = temp.resolve("key");
        assertThatThrownBy(() -> SecretCipher.fromFile(path)).isInstanceOf(SecretStorageException.class);
        Files.write(path, new byte[31]);
        assertThatThrownBy(() -> SecretCipher.fromFile(path)).isInstanceOf(SecretStorageException.class);
        Files.write(path, new byte[33]);
        assertThatThrownBy(() -> SecretCipher.fromFile(path)).isInstanceOf(SecretStorageException.class);
        Files.write(path, key());
        var first = SecretCipher.fromFile(path);
        var value = first.encrypt(secret, "draft:1");
        assertThat(SecretCipher.fromFile(path).decrypt(value, "draft:1")).isEqualTo(secret);
    }

    private byte[] key() { var bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return bytes; }
}
