CREATE TABLE configuration_profile (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Profile UUID',
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Owner UUID',
    name VARCHAR(128) NOT NULL COMMENT 'Display name',
    version BIGINT NOT NULL DEFAULT 1 COMMENT 'Profile CAS version',
    current_release_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Current immutable release UUID',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Deleted profiles cannot be downloaded',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Created UTC',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'Updated UTC',
    PRIMARY KEY (id),
    KEY idx_profile_owner (owner_id,is_deleted,create_time,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Independent configuration profiles';

CREATE TABLE profile_draft (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Draft UUID',
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Owner UUID',
    profile_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Profile UUID',
    version BIGINT NOT NULL DEFAULT 1 COMMENT 'Draft CAS version',
    document_cipher LONGTEXT NOT NULL COMMENT 'Encrypted typed profile document',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Created UTC',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'Updated UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_draft_profile (owner_id,profile_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Mutable unpublished profile definitions';
