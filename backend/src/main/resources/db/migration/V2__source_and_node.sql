CREATE TABLE subscription_source (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Source UUID',
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Owner UUID',
    name VARCHAR(128) NOT NULL COMMENT 'Display name',
    config_version BIGINT NOT NULL COMMENT 'Configuration CAS version',
    snapshot_version BIGINT NOT NULL DEFAULT 0 COMMENT 'Effective successful snapshot version',
    is_enabled TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT 'Automatic refresh enabled',
    refresh_interval_seconds INT NOT NULL DEFAULT 21600 COMMENT 'Refresh interval',
    input_format VARCHAR(32) NOT NULL DEFAULT 'AUTO' COMMENT 'Subscription parser selection',
    request_cipher MEDIUMTEXT NOT NULL COMMENT 'Encrypted URL, headers, UA, proxy alias',
    sync_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/OK/FAILED/EMPTY_PENDING',
    last_attempt_time DATETIME(6) NULL COMMENT 'Last fetch attempt UTC',
    last_success_time DATETIME(6) NULL COMMENT 'Last committed success UTC',
    next_refresh_time DATETIME(6) NULL COMMENT 'Next scheduled refresh UTC',
    last_error_code VARCHAR(64) NULL COMMENT 'Safe error category',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Created UTC',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'Updated UTC',
    PRIMARY KEY (id),
    KEY idx_source_owner (owner_id,create_time,id),
    KEY idx_source_due (is_enabled,next_refresh_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Upstream subscriptions';

CREATE TABLE source_snapshot (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Snapshot UUID',
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Owner UUID',
    source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Source UUID',
    snapshot_version BIGINT NOT NULL COMMENT 'Successful source version',
    payload_cipher LONGTEXT NOT NULL COMMENT 'Encrypted raw response and parsed node snapshot',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Created UTC',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'Updated UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_snapshot_source_version (owner_id,source_id,snapshot_version),
    KEY idx_snapshot_retention (owner_id,source_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Immutable successful source snapshots';

CREATE TABLE node (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Stable node UUID',
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Owner UUID',
    source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Null for local node',
    name_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'SHA256 of exact UTF8 name',
    original_name TEXT COLLATE utf8mb4_bin NOT NULL COMMENT 'Exact original name without normalization',
    protocol VARCHAR(32) NOT NULL COMMENT 'Protocol type',
    region VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'Name-based hint only',
    snapshot_version BIGINT NOT NULL DEFAULT 0 COMMENT 'Last observed source snapshot',
    connection_cipher MEDIUMTEXT NOT NULL COMMENT 'Authenticated encrypted complete NodeSpec',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Created UTC',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'Updated UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_node_source_name (owner_id,source_id,name_hash),
    KEY idx_node_owner_page (owner_id,create_time,id),
    KEY idx_node_owner_filter (owner_id,protocol,region)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Shared owner node pool';
