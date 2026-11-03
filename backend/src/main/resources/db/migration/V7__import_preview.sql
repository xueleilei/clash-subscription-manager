CREATE TABLE import_preview (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Preview UUID',
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Owner UUID',
    kind VARCHAR(16) NOT NULL COMMENT 'IMPORT/BINDING/REMOVAL',
    data_revision BIGINT NOT NULL COMMENT 'Expected owner revision',
    payload_cipher LONGTEXT NOT NULL COMMENT 'Encrypted typed preview payload',
    create_time DATETIME(6) NOT NULL COMMENT 'Created UTC',
    expires_at DATETIME(6) NOT NULL COMMENT 'Thirty-minute deadline',
    is_consumed TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Single-use confirmation',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'Updated UTC',
    PRIMARY KEY (id), KEY idx_preview_owner (owner_id,kind,expires_at), KEY idx_preview_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Version-bound encrypted import and mutation previews';
CREATE TABLE import_batch (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Import batch UUID',
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Owner UUID',
    profile_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'New imported profile UUID',
    payload_cipher LONGTEXT NOT NULL COMMENT 'Encrypted original YAML independent of preview lifetime',
    create_time DATETIME(6) NOT NULL COMMENT 'Created UTC',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'Updated UTC',
    PRIMARY KEY (id), UNIQUE KEY uk_batch_profile (owner_id,profile_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Retained original import documents';
