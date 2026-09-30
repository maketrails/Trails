# Server tools

Developer tools that run the server's own code against its data. They live in the
`tools` source set of `:server`, which sees the `internal` declarations of `main` but is
not part of the server JAR.

## Speed chart

Renders the speed of a device over time as a self-contained HTML file, coloured by the
movement mode (walking, bike, travel) the optimizer logs.

The raw positions are read straight from the server's SQLite database and run through
`TrackPipeline` in batches of `TrailOptimizer.BATCH_SIZE`, exactly like a full
re-optimization on the server. The chart therefore always shows what the current code
produces, not what is stored — no server restart needed to try out a change to the
algorithm.

```bash
./gradlew :server:speedChart --args="--device <device uuid>"
```

| Option     | Default                   | Meaning                                                     |
|------------|---------------------------|-------------------------------------------------------------|
| `--device` | —                         | UUID of the device, see the `devices` table                 |
| `--db`     | `server/data/database.db` | SQLite database of the server                               |
| `--from`   | —                         | Only show positions recorded from this local time on        |
| `--to`     | —                         | Only show positions recorded before this local time         |
| `--out`    | `speed-charts/speed.html` | HTML file to write; `speed-charts/` is ignored by git       |

Paths are relative to the repository root, times are local ISO times such as
`2026-09-29T08:30`. Besides the chart, the tool prints every run of the same movement
mode, the lines the server logs while optimizing.

In the chart, drag across the plot to zoom in; the zoom is kept in the URL
(`#from=…&to=…`), so a reload or a shared link opens the same range. *Reset zoom* shows
everything again, and *Table view* lists every value.

Only SQLite databases are supported.
