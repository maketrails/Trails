# Timeline redesign — proposals

Issue #138. Three working prototypes live in the web app's lab at **`/lab/timeline`**
(not linked from anywhere, generated data, no server calls, does not touch the real map).
Code: `web/src/routes/lab/timeline/` and `web/src/lib/lab/timeline/` — delete both folders
(plus the `lab` group in `web/src/lib/i18n/locales/*.json`) to remove the lab again.

The lab has a phone/wide toggle to check each proposal at 360 px, and a "Map:" line
standing in for what the map would frame.

## What is wrong with the current timeline

The current timeline (`Timeline.svelte` + `MovementLane`, with `TrailToolbar` and
`RangeShortcuts` above it) is a zoomable horizontal track. After #134–#137 it is solid
technically — window model, gestures, touch, snapping — but as a way to *read* a history:

1. **It does not exist on phones.** It lives in the map overlay strip between the card and
   the camera switch, which is `hidden` below `md`. A phone user has no timeline at all.
2. **It answers "how did I move", not "where was I".** Only movements are drawn. Stays —
   most of any day — are the empty track between blocks, without a place or a duration.
3. **Gaps look like stays.** A stretch the device reported nothing for is drawn exactly
   like a stretch it sat still.
4. **The opening week is unreadable.** Seven days squeezed into one strip turn a 10-minute
   walk into a hairline; days can't be compared ("did I leave later than usual?").
5. **The overview carries no data.** The scrollbar below shows where the window is, but
   not what the rest of the history holds, so finding "the afternoon I went to the lake"
   means zooming out and scanning.
6. **Controls are spread over three places.** Mode, source and shortcuts above; range
   label, export and zoom buttons in the header; scrollbar below. On a narrow strip the
   toolbar wraps.
7. **Gestures are overloaded and invisible.** Mouse drag marks a range, wheel pans,
   ctrl+wheel zooms, a touch long-press marks a range. Nothing on screen teaches that.
8. **Hover says only the time.** What was happening at that moment has to be read off the map.

## Proposal A — Day strip

One row per day, newest on top, each row the same 24 hours wide (hour scale shared by
all rows). Movements are coloured blocks, gaps hatched, the not-yet part of today dashed;
the total distance sits at the end of each row. Tapping a day opens it in place into a
full-width detail (place labels above the production `MovementLane`, hour ticks below);
tapping a movement there focuses it on the map.

- **Pros:** days are directly comparable — routines and outliers jump out. No zoom or pan
  gesture at all, only taps, so it is the most touch- and phone-friendly. A day is the
  natural unit people remember; the shortcuts mostly become unnecessary (a row *is* "Today").
  Very cheap to render.
- **Cons:** a fixed 24 h row is too coarse for minutes (a 2-minute walk is a sliver even
  when open). Long histories (a month+) mean a lot of rows. Things crossing midnight are
  split. Selecting an arbitrary range (e.g. for export) needs another mechanism.
- **Mode switcher / shortcuts:** the mode switch sits on top; the shortcuts are replaced
  by the day rows.

## Proposal B — Scrubber with focus + context

The whole history on top as a data-carrying overview (movements coloured, gaps hatched,
midnights marked) with a brush: drag it to move the window, drag its edges to resize, press
beside it to jump there. Below it the detail, with a **fixed needle in the centre**: dragging
slides the history underneath (one finger or mouse — no mode), pinch or ctrl+wheel zooms
around the needle. A readout above the detail says what was happening at the needle
("Cycling · 3.2 km · 10:12–10:40", "At Work · 4 hr 5 min", "No data"). Mode switch and range
shortcuts share a single bar.

- **Pros:** closest to today's architecture — it is built from the existing
  `createTimelineWindow`, `timelineGestures`, `axisFor`/`TimelineAxis` and `MovementLane`;
  the overview simply replaces `TimelineScrollbar`. One drag gesture with one meaning. The
  needle + readout is a much better "what happened at this moment" than hover, and works the
  same on touch. The overview fixes "where in the history is the interesting part".
- **Cons:** still a horizontal track, so still best on wide screens (it works at 360 px but
  the overview gets dense). Marking a range for export needs to come back (e.g. "mark from
  needle" or the brush itself as the selection). The centred needle can't sit on the very
  edges without padding the bounds (done here by half a window).
- **Mode switcher / shortcuts:** one bar above the overview; shortcuts set the brush.

## Proposal C — Story list

The history as what it means to a person: a vertical list of stays and trips, newest first,
grouped by day under sticky headers (with the day's distance). A stay shows the place, time
range and duration; a trip its type, distance, "Home → Station" and duration, drawn on a
coloured rail; a gap reads "No data for 2 hr 40 min". A slim scrubber above shows the range
at a glance with the part the list currently shows framed; pressing or dragging it jumps the
list there. Tapping an entry focuses it on the map. Shortcuts filter the list.

- **Pros:** the only proposal that answers "where was I" in words. Native scroll — perfect
  on phones and fits the existing card (no overlay strip needed). Accessible by default
  (it is a list of buttons). Place names make the history searchable later.
- **Cons:** needs data the web app doesn't have today: stays and gaps must be derived
  (stays = time between movements; gaps = point spacing above a threshold), and place names
  need reverse geocoding (the server already has a Nominatim service in `data/`). Loses the
  continuous, visual sense of time; arbitrary-range selection again needs another mechanism.
- **Mode switcher / shortcuts:** mode switch and shortcuts on top, shortcuts as filters.

## Recommendation

Don't pick one — the proposals cover different screens and questions:

1. **Adopt B as the timeline in the map strip (md and up).** It is an evolution of what
   exists, fixes problems 4–8, and is mostly a re-arrangement of existing modules:
   overview with brush instead of the scrollbar, centred needle + readout instead of hover,
   one toolbar. Bring range marking back on top of it before it replaces the current one.
2. **Adopt C as the history view in the card, and with it on phones.** This fixes problem 1
   (no timeline on phones) and 2–3. Start without place names (fall back to "Stay · 2 hr"),
   derive stays and gaps on the client, and add reverse-geocoded names server-side later.
   Tapping an entry should drive the same map focus as the strip.
3. **Fold A's best idea into C:** a mini day row (the 24 h strip) in each sticky day header
   gives the at-a-glance comparison without a separate view.

Both B and C need gaps to be first-class (hatched / "No data"), which is worth doing in the
current timeline right away, independent of the redesign.
