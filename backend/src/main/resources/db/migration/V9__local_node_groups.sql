CREATE TABLE local_node_group (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Manual source folder UUID',
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Owner UUID',
    name VARCHAR(64) COLLATE utf8mb4_0900_as_ci NOT NULL COMMENT 'Owner-visible folder name',
    version BIGINT NOT NULL DEFAULT 0 COMMENT 'Name revision for rename and deletion',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Created UTC',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'Updated UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_local_group_owner_name (owner_id,name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Manual node source folders, independent of profile proxy groups';

ALTER TABLE node
    ADD COLUMN local_group_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Manual source folder; null for ungrouped or remote nodes',
    ADD KEY idx_node_owner_local_group (owner_id,source_id,is_active,local_group_id,create_time,id);
