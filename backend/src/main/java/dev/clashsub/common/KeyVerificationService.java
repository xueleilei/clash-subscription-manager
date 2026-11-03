package dev.clashsub.common;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class KeyVerificationService implements ApplicationRunner {
    private static final String CONTEXT = "application-key-checkpoint:v1";
    private static final String MARKER = "clash-subscription-manager-key-check:v1";
    private final KeyCheckpointMapper mapper;
    private final EncryptedDocuments documents;
    public KeyVerificationService(KeyCheckpointMapper mapper, EncryptedDocuments documents) { this.mapper = mapper; this.documents = documents; }
    @Override @Transactional public void run(ApplicationArguments arguments) { verify(); }
    @Transactional public void verify() {
        String value = mapper.checkpoint();
        if (value == null) {
            for (var sample : mapper.legacySamples()) { documents.read(sample.ciphertext(), sample.aadContext(), Object.class); }
            mapper.insert(documents.write(MARKER, CONTEXT));
            value = mapper.checkpoint();
        }
        if (!MARKER.equals(documents.read(value, CONTEXT, String.class))) { throw new SecretStorageException(); }
    }
}
