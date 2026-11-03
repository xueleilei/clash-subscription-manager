package dev.clashsub.common;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EncryptionConfig {
    @Bean SecretCipher secretCipher(@Value("${clash.encryption-key-file:}") String filename) {
        if (filename.isBlank()) { throw new SecretStorageException(); }
        return SecretCipher.fromFile(Path.of(filename));
    }
}
