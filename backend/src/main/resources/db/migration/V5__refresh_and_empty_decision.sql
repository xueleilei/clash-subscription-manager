ALTER TABLE node ADD COLUMN is_active TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT 'Inactive identity tombstone; never restores memberships' AFTER snapshot_version;
CREATE TABLE refresh_attempt (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Attempt UUID',
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Owner UUID',
    source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Source UUID',
    config_version BIGINT NOT NULL COMMENT 'Configuration at fetch start',
    snapshot_version BIGINT NOT NULL COMMENT 'Effective version at fetch start',
    status VARCHAR(32) NOT NULL COMMENT 'RUNNING/SUCCESS/UNCHANGED/EMPTY_PENDING/EMPTY_KEPT/FAILED/CONFLICT',
    payload_cipher MEDIUMTEXT NULL COMMENT 'Encrypted safe outcome with reference changes',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Started UTC',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'Updated UTC',
    PRIMARY KEY (id),
    KEY idx_attempt_source (owner_id,source_id,create_time,id),
    KEY idx_attempt_retention (status,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Durable refresh outcomes';
CREATE TABLE pending_empty_decision (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Decision UUID',
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Owner UUID',
    source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Source UUID',
    config_version BIGINT NOT NULL COMMENT 'Expected source configuration',
    snapshot_version BIGINT NOT NULL COMMENT 'Expected effective source snapshot',
    candidate_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Raw empty response SHA256',
    state VARCHAR(16) NOT NULL COMMENT 'PENDING/KEPT/CLEARED/EXPIRED',
    payload_cipher LONGTEXT NOT NULL COMMENT 'Encrypted empty candidate bytes',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Created UTC',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'Updated UTC',
    PRIMARY KEY (id),
    KEY idx_empty_source (owner_id,source_id,state,config_version,snapshot_version),
    KEY idx_empty_owner (owner_id,state,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Version-bound explicit empty-result choices';
