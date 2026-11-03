CREATE TABLE app_user (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'User UUID',
    username VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT 'Immutable login name',
    password_hash VARCHAR(255) NOT NULL COMMENT 'Delegating password encoder hash',
    data_revision BIGINT NOT NULL DEFAULT 0 COMMENT 'Owner mutation fence',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Created at UTC',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'Updated at UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Application users';
