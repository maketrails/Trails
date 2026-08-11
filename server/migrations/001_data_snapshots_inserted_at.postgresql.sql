-- Adds data_snapshots.inserted_at: when a row was stored, as opposed to
-- "timestamp", which is when its position was recorded.
--
-- Rows that predate the column are backfilled with their recorded timestamp. That is
-- not the truth — it is the closest reconstruction available, and it is monotonic in
-- the same direction, so a client reading incrementally from a cursor stays correct
-- across the backfilled part of the history.
--
-- The type mirrors the existing "timestamp" column, which Exposed maps to TIMESTAMP.

BEGIN;

ALTER TABLE data_snapshots
    ADD COLUMN inserted_at TIMESTAMP;

UPDATE data_snapshots
SET inserted_at = "timestamp"
WHERE inserted_at IS NULL;

ALTER TABLE data_snapshots
    ALTER COLUMN inserted_at SET NOT NULL;

-- Makes "everything this device stored since X" a range scan instead of a walk over
-- the device's whole series.
CREATE INDEX IF NOT EXISTS data_snapshots_device_inserted_at
    ON data_snapshots (device, inserted_at);

COMMIT;
