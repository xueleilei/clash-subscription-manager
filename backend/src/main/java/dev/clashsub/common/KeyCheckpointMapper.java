package dev.clashsub.common;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface KeyCheckpointMapper {
    record Sample(String ciphertext, String aadContext) { }
    @Select("SELECT check_cipher FROM encryption_checkpoint WHERE id = 1 FOR UPDATE") String checkpoint();
    @Insert("INSERT IGNORE INTO encryption_checkpoint (id, check_cipher) VALUES (1, #{ciphertext})") int insert(@Param("ciphertext") String ciphertext);
    /** One authenticated sample per legacy encrypted store; SQL identifiers are fixed, never user input. */
    @Select("""
        (SELECT request_cipher AS ciphertext, CONCAT('source:',owner_id,':',id) AS aad_context FROM subscription_source LIMIT 1)
        UNION ALL (SELECT payload_cipher, CONCAT('source-snapshot:',owner_id,':',id) FROM source_snapshot LIMIT 1)
        UNION ALL (SELECT connection_cipher, CONCAT('node:',owner_id,':',id) FROM node LIMIT 1)
        UNION ALL (SELECT document_cipher, CONCAT('profile-draft:',owner_id,':',profile_id) FROM profile_draft LIMIT 1)
        UNION ALL (SELECT payload_cipher, CONCAT('profile-release:',owner_id,':',id) FROM profile_release LIMIT 1)
        UNION ALL (SELECT token_cipher, CONCAT('subscription-token:',owner_id,':',id) FROM configuration_profile WHERE token_cipher IS NOT NULL LIMIT 1)
        UNION ALL (SELECT payload_cipher, CONCAT('refresh-attempt:',owner_id,':',id) FROM refresh_attempt WHERE payload_cipher IS NOT NULL LIMIT 1)
        UNION ALL (SELECT payload_cipher, CONCAT('empty-decision:',owner_id,':',id) FROM pending_empty_decision LIMIT 1)
        UNION ALL (SELECT payload_cipher, CONCAT('import-preview:',owner_id,':',id) FROM import_preview LIMIT 1)
        UNION ALL (SELECT payload_cipher, CONCAT('import-batch:',owner_id,':',id) FROM import_batch LIMIT 1)
        """) List<Sample> legacySamples();
}
