package dev.clashsub.common;

import dev.clashsub.profile.ProfileService;
import dev.clashsub.support.MySqlIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class EncryptionStartupIT extends MySqlIntegrationTest {
    @Autowired KeyCheckpointMapper mapper;
    @Autowired KeyVerificationService verification;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProfileService profiles;
    @Test void initializedDatabaseRejectsAnotherKeyBeforeServingRequests() {
        byte[] wrong = new byte[32]; wrong[0] = 1;
        var invalid = new KeyVerificationService(mapper, new EncryptedDocuments(new SecretCipher(wrong), new JsonMapper()));
        assertThatThrownBy(invalid::verify).isInstanceOf(SecretStorageException.class);
        assertThatCode(verification::verify).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM encryption_checkpoint", Integer.class)).isEqualTo(1);
    }
    @Test @Transactional void existingEncryptedDataIsVerifiedBeforeInitializingCheckpoint() {
        UUID owner = UUID.fromString(jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", String.class, ADMIN));
        profiles.create(owner, "Key migration fixture");
        jdbc.update("DELETE FROM encryption_checkpoint");
        byte[] wrong = new byte[32]; wrong[0] = 1;
        var invalid = new KeyVerificationService(mapper, new EncryptedDocuments(new SecretCipher(wrong), new JsonMapper()));
        assertThatThrownBy(invalid::verify).isInstanceOf(SecretStorageException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM encryption_checkpoint", Integer.class)).isZero();
        verification.verify();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM encryption_checkpoint", Integer.class)).isEqualTo(1);
    }
}
