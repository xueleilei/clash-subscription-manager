ALTER TABLE node
    ADD COLUMN region_override VARCHAR(16) NULL COMMENT 'Explicit local region code, null for name-based recognition' AFTER region;
