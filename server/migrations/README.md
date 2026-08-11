# Migrations

The server creates missing **tables** on start (`SchemaUtils.create` in
[DatabaseManager.kt](../src/main/kotlin/es/jvbabi/trails/database/DatabaseManager.kt)),
but it never alters an existing one. A change to a table that is already out there
therefore needs a migration here, and it has to be applied **before** the new server
version starts — it would otherwise query a column the database does not have yet.

- One numbered migration per change, in one file per dialect
  (`<number>_<what>.<dialect>.sql`). Both files must end up with the same schema; they
  differ only where the dialects force them to.
- A fresh installation needs none of them: the tables are created from the Kotlin
  definitions, which already include everything the migrations add.
- Keep index names identical to the ones Exposed generates
  (`<table>_<column>_<column>`), so a later `SchemaUtils` run recognises them as
  present.

## Applying

PostgreSQL:

```bash
psql -h <host> -U <user> -d <database> -f server/migrations/001_data_snapshots_inserted_at.postgresql.sql
```

SQLite (stop the server first — the file is single-writer):

```bash
sqlite3 server/data/database.db < server/migrations/001_data_snapshots_inserted_at.sqlite.sql
```

Take a backup of the database before either.
