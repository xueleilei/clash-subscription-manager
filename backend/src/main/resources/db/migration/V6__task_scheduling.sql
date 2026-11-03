ALTER TABLE subscription_source
    ADD COLUMN active_attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Current queued/running attempt UUID',
    ADD KEY idx_source_active_attempt (active_attempt_id);
ALTER TABLE refresh_attempt
    ADD COLUMN trigger_kind VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/SCHEDULED/RECOVERY',
    ADD COLUMN lease_until DATETIME(6) NULL COMMENT 'Total execution deadline UTC',
    ADD KEY idx_attempt_dispatch (status,create_time,id),
    ADD KEY idx_attempt_lease (status,lease_until);
UPDATE subscription_source SET refresh_interval_seconds=300 WHERE refresh_interval_seconds<300;
