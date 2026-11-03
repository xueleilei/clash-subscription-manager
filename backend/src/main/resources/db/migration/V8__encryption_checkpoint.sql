CREATE TABLE encryption_checkpoint (
    id TINYINT UNSIGNED NOT NULL COMMENT 'Singleton row, always 1',
    check_cipher TEXT NOT NULL COMMENT 'Authenticated key verification marker, not key material',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Created UTC',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'Updated UTC',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Fails startup if a restored database uses another encryption key';
