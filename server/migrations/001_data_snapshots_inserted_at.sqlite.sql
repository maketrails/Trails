-- Adds data_snapshots.inserted_at: when a row was stored, as opposed to
-- "timestamp", which is when its position was recorded.
--
-- Rows that predate the column are backfilled with their recorded timestamp. That is
-- not the truth — it is the closest reconstruction available, and it is monotonic in
-- the same direction, so a client reading incrementally from a cursor stays correct
-- across the backfilled part of the history.
--
-- SQLite cannot add a NOT NULL column without a default and cannot drop that default
-- afterwards without rebuilding the table. The default is left in place: it is never
-- reached, because the column has a client-side default in Exposed and every insert
-- therefore carries a value.

BEGIN;

ALTER TABLE data_snapshots
    ADD COLUMN inserted_at TEXT NOT NULL DEFAULT '1970-01-01 00:00:00.000';

UPDATE data_snapshots
SET inserted_at = "timestamp";

-- Makes "everything this device stored since X" a range scan instead of a walk over
-- the device's whole series.
CREATE INDEX IF NOT EXISTS data_snapshots_device_inserted_at
    ON data_snapshots (device, inserted_at);

COMMIT;
