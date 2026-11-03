ALTER TABLE configuration_profile
    ADD COLUMN token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'SHA256 of subscription token',
    ADD COLUMN token_cipher TEXT NULL COMMENT 'Encrypted recoverable subscription token',
    ADD UNIQUE KEY uk_profile_token_hash (token_hash);

CREATE TABLE profile_release (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Release UUID',
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Owner UUID',
    profile_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Profile UUID',
    version BIGINT NOT NULL COMMENT 'Monotonic release version',
    definition_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Canonical definition SHA256',
    yaml_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Exact output bytes SHA256',
    node_count INT NOT NULL COMMENT 'Unique exported nodes',
    reason VARCHAR(32) NOT NULL COMMENT 'MANUAL/REFRESH/SOURCE_REMOVAL/RESTORE',
    payload_cipher LONGTEXT NOT NULL COMMENT 'Encrypted immutable definition, nodes, source versions and YAML',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Created UTC',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Immutable creation timestamp UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_release_profile_version (owner_id,profile_id,version),
    KEY idx_release_retention (owner_id,profile_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Immutable validated configuration artifacts';
