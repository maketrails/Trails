#!/usr/bin/env python3
"""
Renders the speed of a device over time as a self-contained HTML chart.

Usage:
    scripts/speed_chart.py --device <device uuid> [--db server/data/database.db] [--raw]
                           [--from 2026-09-23] [--to 2026-09-30] [--unfiltered]
                           [--max-kmh 400] [--out speed.html]

Reads the snapshots straight from the server's SQLite database — the optimized series by
default, the raw measurements with --raw — and computes the speed between consecutive
positions the same way `TrailOptimizer.speedKmh` does: haversine distance over the time
between the two recordings. Pairs without a positive time difference are skipped, and the
line is broken wherever the device stopped reporting for longer than the optimizer's
segment gap, so a pause does not show up as a slow crawl.

Before the speeds are computed, the later stages of `TrailOptimizer` are applied to every
segment — dropping stale repeats of the previous fix and unreachable jumps at the segment
edges, collapsing pauses and smoothing the movement in between — so the chart shows what
the optimizer produces. The line is coloured by the movement mode the optimizer logs for
every leg between two pauses: walking, bike or travel. --unfiltered shows the series as stored.

Timestamps are shown as stored, i.e. in the server's local time zone.
"""

from __future__ import annotations

import argparse
import json
import math
import sqlite3
import sys
import uuid
from datetime import datetime
from typing import NamedTuple

EARTH_RADIUS_METERS = 6_371_000.0

# Mirror the constants of TrailOptimizer.
SEGMENT_GAP_SECONDS = 300.0
MAX_SPEED_METERS_PER_SECOND = 100.0
STATIONARY_RADIUS_METERS = 20.0
SPIKE_RETURN_RATIO = 0.35
SPIKE_MIN_EXCURSION_METERS = 25.0
STATIONARY_MIN_SECONDS = 20.0
STATIONARY_MIN_POINTS = 3
SMOOTHING_SIGMA_SECONDS = 10.0
SMOOTHING_WINDOW_SECONDS = 2 * SMOOTHING_SIGMA_SECONDS
MOVEMENT_SPEED_PERCENTILE = 0.85
WALKING_MAX_KMH = 14.0
BIKE_MAX_KMH = 30.0
MOVEMENT_MIN_DISTANCE_METERS = 50.0
PAUSE_MAX_KMH = 5.0
TRIP_MAX_PAUSE_SECONDS = 300.0
MODE_MIN_SECONDS = {"walking": 300.0, "bike": 120.0, "travel": 120.0}
INTERRUPTION_MIN_SECONDS = 300.0
BIKE_MAX_SLOWDOWNS_PER_KM = 1.5
SLOWDOWN_RATIO = 0.5
SLOWDOWN_RECOVERY_RATIO = 0.75
SLOWDOWN_MIN_DISTANCE_METERS = 1_000.0


class Position(NamedTuple):
    timestamp: datetime
    latitude: float
    longitude: float
    accuracy: float


def distance(first: Position, second: Position) -> float:
    d_lat = math.radians(second.latitude - first.latitude)
    d_lon = math.radians(second.longitude - first.longitude)
    a = (math.sin(d_lat / 2) ** 2 +
         math.cos(math.radians(first.latitude)) * math.cos(math.radians(second.latitude)) * math.sin(d_lon / 2) ** 2)
    return EARTH_RADIUS_METERS * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def seconds(first: Position, second: Position) -> float:
    return (second.timestamp - first.timestamp).total_seconds()


def unreachable(first: Position, second: Position) -> bool:
    elapsed = seconds(first, second)
    return elapsed <= 0 or distance(first, second) / elapsed > MAX_SPEED_METERS_PER_SECOND


def split_segments(positions: list[Position]) -> list[list[Position]]:
    segments: list[list[Position]] = []
    for position in positions:
        if not segments or seconds(segments[-1][-1], position) > SEGMENT_GAP_SECONDS:
            segments.append([position])
        else:
            segments[-1].append(position)
    return segments


def is_spike(previous: Position, current: Position, following: Position) -> bool:
    """Mirrors TrailOptimizer.isSpike."""
    to_current, from_current = distance(previous, current), distance(current, following)
    returns = distance(previous, following) <= SPIKE_RETURN_RATIO * (to_current + from_current)
    far_enough = min(to_current, from_current) >= max(SPIKE_MIN_EXCURSION_METERS, previous.accuracy + current.accuracy)
    return returns and far_enough


def drop_gap_spikes(positions: list[Position]) -> list[Position]:
    """Mirrors TrailOptimizer.dropGapSpikes."""
    kept = []
    for index, position in enumerate(positions):
        if 0 < index < len(positions) - 1:
            previous, following = positions[index - 1], positions[index + 1]
            gap_before = seconds(previous, position) > SEGMENT_GAP_SECONDS
            gap_after = seconds(position, following) > SEGMENT_GAP_SECONDS
            if gap_before != gap_after and is_spike(previous, position, following):
                continue
        kept.append(position)
    return kept


def drop_stale_repeats(segment: list[Position]) -> list[Position]:
    """Mirrors TrailOptimizer.dropStaleRepeats."""
    if len(segment) < 3:
        return segment

    survivors = [segment[0]]
    for current, following in zip(segment[1:-1], segment[2:]):
        previous = survivors[-1]
        repeats = (current.latitude, current.longitude) == (previous.latitude, previous.longitude)
        moving = distance(previous, following) > max(STATIONARY_RADIUS_METERS, previous.accuracy + following.accuracy)
        if not (repeats and moving):
            survivors.append(current)
    survivors.append(segment[-1])
    return survivors


def drop_edge_jumps(segment: list[Position]) -> list[Position]:
    """Mirrors the segment edge handling of TrailOptimizer.dropSpikes."""
    if len(segment) >= 3 and unreachable(segment[0], segment[1]) and not unreachable(segment[1], segment[2]):
        segment = segment[1:]
    if len(segment) >= 3 and unreachable(segment[-2], segment[-1]) and not unreachable(segment[-3], segment[-2]):
        segment = segment[:-1]
    return segment


def weighted_center(positions: list[Position]) -> tuple[float, float]:
    weights = [1.0 / max(position.accuracy, 1.0) ** 2 for position in positions]
    total = sum(weights)
    return (sum(p.latitude * w for p, w in zip(positions, weights)) / total,
            sum(p.longitude * w for p, w in zip(positions, weights)) / total)


def collapse_stationary(segment: list[Position]) -> list[Position]:
    """Mirrors TrailOptimizer.collapseStationary."""
    output: list[Position] = []
    index = 0
    while index < len(segment):
        anchor = segment[index]
        cluster = [anchor]
        center = (anchor.latitude, anchor.longitude)
        follower = index + 1
        while follower < len(segment):
            candidate = segment[follower]
            to_center = distance(Position(anchor.timestamp, *center, anchor.accuracy), candidate)
            if max(distance(anchor, candidate), to_center) > STATIONARY_RADIUS_METERS:
                break
            cluster.append(candidate)
            center = weighted_center(cluster)
            follower += 1

        if len(cluster) >= STATIONARY_MIN_POINTS and seconds(cluster[0], cluster[-1]) >= STATIONARY_MIN_SECONDS:
            accuracy = min(p.accuracy for p in cluster)
            output.append(cluster[0]._replace(latitude=center[0], longitude=center[1], accuracy=accuracy))
            output.append(cluster[-1]._replace(latitude=center[0], longitude=center[1], accuracy=accuracy))
        else:
            output.extend(cluster)
        index = follower
    return output


def smooth(segment: list[Position]) -> list[Position]:
    """Mirrors TrailOptimizer.smooth."""
    def same_place(first: Position, second: Position) -> bool:
        return (first.latitude, first.longitude) == (second.latitude, second.longitude)

    last = len(segment) - 1
    pinned = [index in (0, last) or same_place(segment[index - 1], segment[index]) or same_place(segment[index], segment[index + 1])
              for index in range(len(segment))]

    output = []
    for index, position in enumerate(segment):
        if pinned[index]:
            output.append(position)
            continue

        # Weighted linear fit of the offsets over time, read at this position's time.
        sums = [0.0] * 7  # w, w·t, w·t², w·lat, w·lon, w·t·lat, w·t·lon

        def include(neighbour: Position) -> None:
            time = seconds(position, neighbour)
            weight = (math.exp(-time ** 2 / (2 * SMOOTHING_SIGMA_SECONDS ** 2)) /
                      max(neighbour.accuracy, 1.0) ** 2)
            latitude = neighbour.latitude - position.latitude
            longitude = neighbour.longitude - position.longitude
            for slot, value in enumerate((1, time, time * time, latitude, longitude, time * latitude, time * longitude)):
                sums[slot] += weight * value

        include(position)
        for direction in (-1, 1):
            neighbour = index + direction
            while 0 <= neighbour <= last and abs(seconds(segment[neighbour], position)) <= SMOOTHING_WINDOW_SECONDS:
                include(segment[neighbour])
                if pinned[neighbour]:
                    break
                neighbour += direction

        w, wt, wtt, wlat, wlon, wtlat, wtlon = sums
        determinant = w * wtt - wt * wt
        if determinant <= 0:
            output.append(position)
            continue
        output.append(position._replace(latitude=position.latitude + (wtt * wlat - wt * wtlat) / determinant,
                                        longitude=position.longitude + (wtt * wlon - wt * wtlon) / determinant))
    return output


def leg_ranges(segment: list[Position]) -> list[range]:
    """Mirrors TrailOptimizer.legs, as index ranges into the segment."""
    ranges = []
    start = 0
    for index in range(1, len(segment) + 1):
        pause = False
        if index < len(segment):
            previous, current = segment[index - 1], segment[index]
            elapsed = seconds(previous, current)
            pause = (previous.latitude, previous.longitude) == (current.latitude, current.longitude) or \
                (elapsed >= STATIONARY_MIN_SECONDS and distance(previous, current) / elapsed * 3.6 <= PAUSE_MAX_KMH)
        if pause or index == len(segment):
            if index - start >= 2:
                ranges.append(range(start, index))
            start = index
    return ranges


def speed_percentile_kmh(leg: list[Position], percentile: float) -> float | None:
    """Mirrors TrailOptimizer.speedPercentileKmh."""
    steps = sorted((distance(a, b) / seconds(a, b) * 3.6, seconds(a, b)) for a, b in zip(leg, leg[1:]) if seconds(a, b) > 0)
    total = sum(elapsed for _, elapsed in steps)
    if total <= 0:
        return None
    covered = 0.0
    for speed, elapsed in steps:
        covered += elapsed
        if covered >= percentile * total:
            return speed
    return steps[-1][0]


def run_ranges(modes: list[str]) -> list[range]:
    """Mirrors TrailOptimizer.runRanges."""
    runs, start = [], 0
    for index in range(1, len(modes) + 1):
        if index == len(modes) or modes[index] != modes[start]:
            runs.append(range(start, index))
            start = index
    return runs


def slowdowns_per_km(legs: list[list[Position]]) -> float | None:
    """Mirrors TrailOptimizer.slowdownsPerKm."""
    length = sum(distance(a, b) for leg in legs for a, b in zip(leg, leg[1:]))
    if length < SLOWDOWN_MIN_DISTANCE_METERS:
        return None
    positions = [position for leg in legs for position in leg]
    cruising = speed_percentile_kmh(positions, MOVEMENT_SPEED_PERCENTILE)
    if cruising is None:
        return None
    slowdowns, cruising_seen = 0, False
    for a, b in zip(positions, positions[1:]):
        elapsed = seconds(a, b)
        if elapsed <= 0:
            continue
        speed = distance(a, b) / elapsed * 3.6
        if speed >= SLOWDOWN_RECOVERY_RATIO * cruising:
            cruising_seen = True
        elif speed < SLOWDOWN_RATIO * cruising and cruising_seen:
            slowdowns += 1
            cruising_seen = False
    return slowdowns / (length / 1000)


def smooth_movement(trip: list[tuple[range, list[Position], str]]) -> list[str]:
    """Mirrors TrailOptimizer.smoothMovement for a single trip."""
    modes = [mode for _, _, mode in trip]
    for _ in range(len(trip) + 1):
        smooth_runs(trip, modes)
        stop_and_go = [run for run in run_ranges(modes) if modes[run.start] == "bike" and
                       (slowdowns_per_km([trip[index][1] for index in run]) or 0.0) > BIKE_MAX_SLOWDOWNS_PER_KM]
        if not stop_and_go:
            break
        for run in stop_and_go:
            for index in run:
                modes[index] = "travel"
    return modes


def smooth_runs(trip: list[tuple[range, list[Position], str]], modes: list[str]) -> None:
    """Mirrors TrailOptimizer.smoothRuns."""

    def duration(run: range) -> float:
        return seconds(trip[run.start][1][0], trip[run.stop - 1][1][-1])

    while True:
        runs = run_ranges(modes)
        if len(runs) < 2:
            break
        def min_seconds(index: int) -> float:
            own = MODE_MIN_SECONDS[modes[runs[index].start]]
            if 0 < index < len(runs) - 1 and modes[runs[index - 1].start] == modes[runs[index + 1].start]:
                return max(own, INTERRUPTION_MIN_SECONDS)
            return own

        short = [index for index, run in enumerate(runs) if duration(run) < min_seconds(index)]
        if not short:
            break
        shortest = min(short, key=lambda index: duration(runs[index]))
        neighbours = [runs[index] for index in (shortest - 1, shortest + 1) if 0 <= index < len(runs)]
        target = max(neighbours, key=duration)
        for index in runs[shortest]:
            modes[index] = modes[target.start]


def classify_movement(segment: list[Position]) -> list[str | None]:
    """
    The movement mode of every step of [segment] — the step from position i to i + 1 is at
    index i. Mirrors TrailOptimizer.logMovement; a run covers everything between its first and
    last position, steps between two runs or outside of any trip stay None.
    """
    legs = []
    for leg_range in leg_ranges(segment):
        leg = segment[leg_range.start:leg_range.stop]
        if sum(distance(a, b) for a, b in zip(leg, leg[1:])) < MOVEMENT_MIN_DISTANCE_METERS:
            continue
        speed = speed_percentile_kmh(leg, MOVEMENT_SPEED_PERCENTILE)
        if speed is None:
            continue
        legs.append((leg_range, leg, "walking" if speed <= WALKING_MAX_KMH else "bike" if speed <= BIKE_MAX_KMH else "travel"))

    trips: list[list] = []
    for leg in legs:
        if not trips or seconds(trips[-1][-1][1][-1], leg[1][0]) > TRIP_MAX_PAUSE_SECONDS:
            trips.append([leg])
        else:
            trips[-1].append(leg)

    modes: list[str | None] = [None] * max(0, len(segment) - 1)
    for trip in trips:
        smoothed = smooth_movement(trip)
        for run in run_ranges(smoothed):
            run_legs = trip[run.start:run.stop]
            reassigned = sum(1 for index in run if smoothed[index] != trip[index][2])
            length = sum(distance(a, b) for _, leg, _ in run_legs for a, b in zip(leg, leg[1:]))
            print(f"{smoothed[run.start]:8} {run_legs[0][1][0].timestamp} – {run_legs[-1][1][-1].timestamp}  "
                  f"{length / 1000:6.2f} km in {len(run_legs)} legs" + (f", {reassigned} reassigned" if reassigned else ""))
        # A run owns everything from its first to its last position — the stops and the
        # jitter too short to judge in between included, only the gaps between runs stay open.
        for run in run_ranges(smoothed):
            for index in range(trip[run.start][0].start, trip[run.stop - 1][0].stop - 1):
                modes[index] = smoothed[run.start]
    return modes


def main() -> int:
    parser = argparse.ArgumentParser(description="Chart the speed of a device over time.")
    parser.add_argument("--device", required=True, help="Device UUID")
    parser.add_argument("--db", default="server/data/database.db")
    parser.add_argument("--raw", action="store_true", help="Use the raw measurements instead of the optimized series")
    parser.add_argument("--from", dest="start", help="Only positions recorded at or after this local time")
    parser.add_argument("--to", dest="end", help="Only positions recorded before this local time")
    parser.add_argument("--unfiltered", action="store_true", help="Skip the optimizer stages")
    parser.add_argument("--max-kmh", type=float, help="Leave out speeds above this, so a single outlier does not flatten the chart")
    parser.add_argument("--out", default="speed.html")
    args = parser.parse_args()

    device = uuid.UUID(args.device).bytes
    connection = sqlite3.connect(args.db)

    name_row = connection.execute("SELECT display_name FROM devices WHERE id = ?", (device,)).fetchone()
    if name_row is None:
        print(f"Unknown device {args.device}", file=sys.stderr)
        return 1

    query = ("SELECT timestamp, latitude, longitude, location_accuracy FROM data_snapshots "
             "WHERE device = ? AND is_raw = ?")
    parameters: list = [device, 1 if args.raw else 0]
    if args.start:
        query += " AND timestamp >= ?"
        parameters.append(args.start)
    if args.end:
        query += " AND timestamp < ?"
        parameters.append(args.end)
    query += " ORDER BY timestamp"

    positions = [Position(datetime.fromisoformat(t), lat, lon, acc) for t, lat, lon, acc in connection.execute(query, parameters)]

    segments = split_segments(positions if args.unfiltered else drop_gap_spikes(positions))
    if not args.unfiltered:
        segments = [drop_edge_jumps(drop_stale_repeats(segment)) for segment in segments]
        kept = sum(len(segment) for segment in segments)
        print(f"Spike filters dropped {len(positions) - kept} of {len(positions)} positions")
        segments = [smooth(collapse_stationary(segment)) for segment in segments]

    # Each entry is [epoch millis, km/h, starts a new segment, movement mode or None].
    points = []
    for segment in segments:
        new_segment = True
        modes = classify_movement(segment)
        for previous, current, mode in zip(segment, segment[1:], modes):
            elapsed = seconds(previous, current)
            if elapsed <= 0:
                continue
            speed = distance(previous, current) / elapsed * 3.6
            if args.max_kmh is not None and speed > args.max_kmh:
                print(f"Left out {speed:.0f} km/h at {current.timestamp}")
                continue
            points.append([int(current.timestamp.timestamp() * 1000), round(speed, 2), new_segment, mode])
            new_segment = False

    if not points:
        print("No consecutive positions to compute a speed from", file=sys.stderr)
        return 1

    series = "raw measurements" if args.raw else "optimized track"
    if args.unfiltered:
        series += ", unfiltered"
    title = f"{name_row[0]} — speed, {series}"
    html = TEMPLATE.replace("__TITLE__", title).replace("__DATA__", json.dumps(points))

    with open(args.out, "w", encoding="utf-8") as file:
        file.write(html)

    print(f"{len(points)} speeds from {len(positions)} positions written to {args.out}")
    return 0


TEMPLATE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>__TITLE__</title>
<style>
  .viz-root {
    color-scheme: light;
    --surface-1: #fcfcfb;
    --text-primary: #0b0b0b;
    --text-secondary: #52514e;
    --text-muted: #8a8984;
    --grid: #e6e5e1;
    --series-1: #2a78d6;
    --series-2: #eb6834;
    --series-3: #1baf7a;
    --unclassified: #b5b4ae;
  }
  @media (prefers-color-scheme: dark) {
    :root:where(:not([data-theme="light"])) .viz-root {
      color-scheme: dark;
      --surface-1: #1a1a19;
      --text-primary: #ffffff;
      --text-secondary: #c3c2b7;
      --text-muted: #8f8e86;
      --grid: #2e2e2c;
      --series-1: #3987e5;
      --series-2: #d95926;
      --series-3: #199e70;
      --unclassified: #5c5b57;
    }
  }
  html, body { margin: 0; }
  body.viz-root { background: var(--surface-1); color: var(--text-primary); font: 14px/1.4 system-ui, sans-serif; padding: 24px; }
  h1 { font-size: 18px; font-weight: 600; margin: 0 0 4px; }
  .subtitle { color: var(--text-secondary); margin: 0 0 16px; }
  .controls { display: flex; gap: 12px; align-items: center; margin-bottom: 12px; color: var(--text-secondary); }
  .controls button { font: inherit; padding: 4px 10px; border-radius: 6px; border: 1px solid var(--grid); background: transparent; color: var(--text-primary); cursor: pointer; }
  .chart { position: relative; }
  svg { display: block; width: 100%; height: 420px; user-select: none; }
  .axis text { fill: var(--text-muted); font-size: 12px; }
  .grid line { stroke: var(--grid); stroke-width: 1; }
  .line { fill: none; stroke-width: 2; stroke-linejoin: round; stroke-linecap: round; }
  .walking { stroke: var(--series-1); --swatch: var(--series-1); }
  .bike { stroke: var(--series-2); --swatch: var(--series-2); }
  .travel { stroke: var(--series-3); --swatch: var(--series-3); }
  .unclassified { stroke: var(--unclassified); --swatch: var(--unclassified); }
  .legend { display: flex; gap: 16px; margin-bottom: 8px; color: var(--text-secondary); }
  .legend span::before { content: ""; display: inline-block; width: 12px; height: 12px; border-radius: 3px; margin-right: 6px; vertical-align: -1px; background: var(--swatch); }
  .crosshair { stroke: var(--text-muted); stroke-width: 1; stroke-dasharray: 3 3; }
  .marker { fill: var(--swatch); stroke: var(--surface-1); stroke-width: 2; }
  .brush { fill: var(--series-1); fill-opacity: 0.12; }
  .tooltip { position: absolute; pointer-events: none; background: var(--surface-1); border: 1px solid var(--grid); border-radius: 8px; padding: 6px 10px; box-shadow: 0 2px 8px rgb(0 0 0 / 0.12); white-space: nowrap; }
  .tooltip .value { font-weight: 600; font-variant-numeric: tabular-nums; }
  .tooltip .time { color: var(--text-secondary); font-size: 12px; }
  details { margin-top: 16px; color: var(--text-secondary); }
  table { border-collapse: collapse; font-variant-numeric: tabular-nums; margin-top: 8px; }
  td, th { padding: 2px 12px 2px 0; text-align: left; color: var(--text-primary); }
</style>
</head>
<body class="viz-root">
<h1>__TITLE__</h1>
<p class="subtitle">km/h between consecutive positions · drag across the chart to zoom</p>
<div class="controls"><button id="reset">Reset zoom</button><span id="range"></span></div>
<div class="legend"><span class="walking">Walking</span><span class="bike">Bike</span><span class="travel">Travel</span><span class="unclassified">Pause / not classified</span></div>
<div class="chart">
  <svg id="svg"></svg>
  <div class="tooltip" id="tooltip" hidden><div class="value"></div><div class="time"></div></div>
</div>
<details><summary>Table view</summary><table id="table"><thead><tr><th>Time</th><th>km/h</th><th>Mode</th></tr></thead><tbody></tbody></table></details>
<script>
const data = __DATA__;
const svg = document.getElementById("svg");
const tooltip = document.getElementById("tooltip");
const NS = "http://www.w3.org/2000/svg";
const margin = {top: 12, right: 16, bottom: 28, left: 48};
const timeFormat = new Intl.DateTimeFormat(undefined, {dateStyle: "short", timeStyle: "medium"});
const dayFormat = new Intl.DateTimeFormat(undefined, {weekday: "short", day: "numeric", month: "short"});
const hourFormat = new Intl.DateTimeFormat(undefined, {hour: "2-digit", minute: "2-digit"});
const modeNames = {walking: "Walking", bike: "Bike", travel: "Travel"};
const modeName = mode => modeNames[mode] || "Pause / not classified";
const numberFormat = new Intl.NumberFormat(undefined, {maximumFractionDigits: 1});

const fullView = () => [data[0][0], data[data.length - 1][0]];

// The zoom lives in the URL hash as local times, e.g. #from=2026-09-29T16:55:00&to=2026-09-29T17:45:00,
// so a reload or a shared link opens the same range.
const toLocalIso = time => new Date(time - new Date(time).getTimezoneOffset() * 60e3).toISOString().slice(0, 19);

function viewFromHash() {
  const parameters = new URLSearchParams(location.hash.slice(1));
  const from = Date.parse(parameters.get("from")), to = Date.parse(parameters.get("to"));
  return Number.isFinite(from) && Number.isFinite(to) && from < to ? [from, to] : fullView();
}

function storeView() {
  const [start, end] = fullView();
  const hash = view[0] <= start && view[1] >= end ? "" : "#from=" + toLocalIso(view[0]) + "&to=" + toLocalIso(view[1]);
  if (hash !== location.hash) history.replaceState(null, "", hash || location.pathname + location.search);
}

let view = viewFromHash();

function el(name, attributes, parent) {
  const node = document.createElementNS(NS, name);
  for (const [key, value] of Object.entries(attributes)) node.setAttribute(key, value);
  parent.appendChild(node);
  return node;
}

function niceStep(span, count) {
  const raw = span / count;
  const power = Math.pow(10, Math.floor(Math.log10(raw)));
  return [1, 2, 5, 10].map(m => m * power).find(step => step >= raw);
}

function timeTicks(start, end) {
  const hour = 3600e3;
  const steps = [5 * 60e3, 15 * 60e3, 30 * 60e3, hour, 3 * hour, 6 * hour, 12 * hour, 24 * hour, 48 * hour];
  const step = steps.find(s => (end - start) / s <= 8) || steps[steps.length - 1];
  const offset = new Date(start).getTimezoneOffset() * 60e3;
  const ticks = [];
  for (let t = Math.ceil((start - offset) / step) * step + offset; t <= end; t += step) ticks.push(t);
  return {ticks, daily: step >= 24 * hour};
}

function render() {
  svg.replaceChildren();
  const width = svg.clientWidth, height = svg.clientHeight;
  const innerWidth = width - margin.left - margin.right, innerHeight = height - margin.top - margin.bottom;
  const visible = data.filter(d => d[0] >= view[0] && d[0] <= view[1]);
  const maxSpeed = Math.max(1, ...visible.map(d => d[1]));
  const yStep = niceStep(maxSpeed, 5);
  const yMax = Math.ceil(maxSpeed / yStep) * yStep;
  const x = t => margin.left + (t - view[0]) / (view[1] - view[0] || 1) * innerWidth;
  const y = v => margin.top + innerHeight - v / yMax * innerHeight;

  const grid = el("g", {class: "grid"}, svg);
  const axis = el("g", {class: "axis"}, svg);
  for (let v = 0; v <= yMax + 1e-9; v += yStep) {
    el("line", {x1: margin.left, x2: width - margin.right, y1: y(v), y2: y(v)}, grid);
    el("text", {x: margin.left - 8, y: y(v) + 4, "text-anchor": "end"}, axis).textContent = numberFormat.format(v);
  }
  const {ticks, daily} = timeTicks(view[0], view[1]);
  for (const t of ticks) {
    el("text", {x: x(t), y: height - 8, "text-anchor": "middle"}, axis).textContent =
      daily ? dayFormat.format(t) : hourFormat.format(t);
  }

  // One path per run of the same mode; a run continues from the last point of the
  // previous one so the line stays connected where the mode changes.
  const paths = [];
  let previous = null;
  for (const [t, v, newSegment, mode] of visible) {
    const point = x(t).toFixed(1) + "," + y(v).toFixed(1);
    const key = mode || "unclassified";
    const current = paths[paths.length - 1];
    if (newSegment || !current || current.key !== key) {
      paths.push({key, d: (newSegment || !previous ? "M" : "M" + previous + "L") + point});
    } else {
      current.d += "L" + point;
    }
    previous = point;
  }
  for (const {key, d} of paths) el("path", {class: "line " + key, d}, svg);

  const crosshair = el("line", {class: "crosshair", y1: margin.top, y2: margin.top + innerHeight, visibility: "hidden"}, svg);
  const marker = el("circle", {class: "marker", r: 5, visibility: "hidden"}, svg);
  const brush = el("rect", {class: "brush", y: margin.top, height: innerHeight, width: 0, visibility: "hidden"}, svg);
  const hit = el("rect", {x: margin.left, y: margin.top, width: innerWidth, height: innerHeight, fill: "transparent"}, svg);

  const toTime = clientX => view[0] + (clientX - svg.getBoundingClientRect().left - margin.left) / innerWidth * (view[1] - view[0]);
  let dragStart = null;

  hit.addEventListener("pointermove", event => {
    const t = toTime(event.clientX);
    if (dragStart !== null) {
      const a = x(Math.min(dragStart, t)), b = x(Math.max(dragStart, t));
      brush.setAttribute("x", a); brush.setAttribute("width", b - a); brush.setAttribute("visibility", "visible");
    }
    if (!visible.length) return;
    let lo = 0, hi = visible.length - 1;
    while (lo < hi) { const mid = (lo + hi) >> 1; if (visible[mid][0] < t) lo = mid + 1; else hi = mid; }
    if (lo > 0 && t - visible[lo - 1][0] < visible[lo][0] - t) lo--;
    const [pt, pv, , pm] = visible[lo];
    marker.setAttribute("class", "marker " + (pm || "unclassified"));
    crosshair.setAttribute("x1", x(pt)); crosshair.setAttribute("x2", x(pt)); crosshair.setAttribute("visibility", "visible");
    marker.setAttribute("cx", x(pt)); marker.setAttribute("cy", y(pv)); marker.setAttribute("visibility", "visible");
    tooltip.querySelector(".value").textContent = numberFormat.format(pv) + " km/h · " + modeName(pm);
    tooltip.querySelector(".time").textContent = timeFormat.format(pt);
    tooltip.hidden = false;
    const left = x(pt) + 12;
    tooltip.style.left = (left + tooltip.offsetWidth > width ? x(pt) - 12 - tooltip.offsetWidth : left) + "px";
    tooltip.style.top = Math.max(0, y(pv) - tooltip.offsetHeight - 8) + "px";
  });
  hit.addEventListener("pointerleave", () => {
    crosshair.setAttribute("visibility", "hidden"); marker.setAttribute("visibility", "hidden"); tooltip.hidden = true;
  });
  hit.addEventListener("pointerdown", event => { dragStart = toTime(event.clientX); hit.setPointerCapture(event.pointerId); });
  hit.addEventListener("pointerup", event => {
    const end = toTime(event.clientX);
    if (dragStart !== null && Math.abs(x(end) - x(dragStart)) > 5) view = [Math.min(dragStart, end), Math.max(dragStart, end)];
    dragStart = null;
    render();
  });

  document.getElementById("range").textContent = timeFormat.format(view[0]) + " – " + timeFormat.format(view[1]);
  storeView();
}

document.getElementById("reset").addEventListener("click", () => { view = fullView(); render(); });
window.addEventListener("hashchange", () => { view = viewFromHash(); render(); });
window.addEventListener("resize", render);

const body = document.querySelector("#table tbody");
for (const [t, v, , mode] of data) {
  const row = body.insertRow();
  row.insertCell().textContent = timeFormat.format(t);
  row.insertCell().textContent = numberFormat.format(v);
  row.insertCell().textContent = modeName(mode);
}
render();
</script>
</body>
</html>
"""


if __name__ == "__main__":
    sys.exit(main())
