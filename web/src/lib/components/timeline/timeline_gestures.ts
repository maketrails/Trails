/**
 * Wheel, drag and pinch on a timeline track, as a Svelte action.
 *
 * It speaks pixels, not time: what a gesture means for the window is the caller's
 * business, which keeps this usable for any track that scrolls sideways and zooms.
 *
 * Mouse, pen and finger are read differently. A mouse or pen drag is handed to
 * {@link TimelineDrag} (or pans without one), and the wheel moves the window. A finger
 * has no wheel and no hover, so touch goes through {@link TouchRecognizer}: one finger
 * pans and coasts on when flicked, two pinch, a long press starts marking a range and
 * a tap is reported as a tap.
 */

/** What a pointer was doing while it dragged, for gestures that care. */
export interface DragModifiers {
    ctrlKey: boolean;
    metaKey: boolean;
    shiftKey: boolean;
}

/**
 * What one pointer dragging across the track does. Leave it out and a drag pans;
 * supply it and the drag is yours — sweeping out a selection, say — while panning
 * stays on the wheel, the pinch and whatever else the caller offers.
 *
 * On touch, a finger only becomes a drag after a long press: a plain one-finger
 * swipe pans, as it does everywhere else on a touch screen.
 *
 * Positions are 0…1 across the node, the same anchors {@link TimelineGestures.zoom}
 * speaks in.
 */
export interface TimelineDrag {
    /** [target] is what the press landed on, for a drag that starts on something. */
    start(anchor: number, modifiers: DragModifiers, target: EventTarget | null): void;
    move(anchor: number, modifiers: DragModifiers): void;
    end(): void;
}

export interface TimelineGestures {
    /** Move the content by [pixels]; positive scrolls towards the right-hand end. */
    pan(pixels: number): void;
    /**
     * Zoom by [factor] — below 1 zooms in — around [anchor], a 0…1 position across
     * the node.
     */
    zoom(factor: number, anchor: number): void;
    /** Takes over one-pointer drags. Without it, they pan. */
    drag?: TimelineDrag;
    /**
     * A finger touched the track at [anchor] and let go without moving. Without it, a
     * tap is passed to {@link drag} as a drag that went nowhere — what a mouse click is.
     */
    tap?(anchor: number, target: EventTarget | null): void;
}

/**
 * Marks an element inside the track that handles its own drags — the ends of a
 * marked range, say. A press that starts there is left alone.
 *
 * It takes an attribute rather than the child stopping the event, because Svelte
 * delegates a component's own handlers to the document root: the listener here
 * sits on the track itself and would see the press first, whatever the child does
 * about it afterwards.
 */
export const TIMELINE_GRIP = "data-timeline-grip";

/** How hard a pinch or a held-modifier wheel zooms, per pixel of travel. */
const ZOOM_RATE = 0.01;

/** A wheel event reporting lines rather than pixels is worth about this much. */
const LINE_HEIGHT = 16;

/** How far a finger may wander and still be a tap or a long press, in pixels. */
export const TOUCH_SLOP = 10;

/** How long a finger has to rest before it starts marking a range, in milliseconds. */
export const LONG_PRESS_MS = 450;

/** How far back the release speed of a pan is measured, in milliseconds. */
const VELOCITY_WINDOW_MS = 100;

/** Slower than this (pixels per millisecond) and a released pan just stops. */
export const MIN_FLING_VELOCITY = 0.15;

/** How quickly a fling dies down: its speed falls to 1/e over this many milliseconds. */
const FLING_TIME_CONSTANT_MS = 325;

/** Below this speed (pixels per millisecond) a fling is over. */
const FLING_STOP_VELOCITY = 0.02;

/** Fingers carry no modifier keys. */
const NO_MODIFIERS: DragModifiers = {ctrlKey: false, metaKey: false, shiftKey: false};

/**
 * What a touch gesture asks of the track. Positions are client x coordinates; turning
 * them into anchors across the track is the caller's job.
 */
export type TouchIntent =
    /** Move the content by [pixels]; positive scrolls towards the right-hand end. */
    | {type: "pan"; pixels: number}
    /** Zoom by [factor] — below 1 zooms in — around the client x [centre]. */
    | {type: "zoom"; factor: number; centre: number}
    /** A long press began marking a range at [x]. */
    | {type: "select-start"; x: number}
    | {type: "select-move"; x: number}
    | {type: "select-end"}
    /** A finger went down and up without travelling, at [x]. */
    | {type: "tap"; x: number}
    /**
     * A pan was let go of while still moving, at [velocity] pixels per millisecond of
     * finger travel (positive to the right); see {@link flingStep}.
     */
    | {type: "fling"; velocity: number}
    /** A finger came down: whatever is still coasting stops under it. */
    | {type: "stop"};

type Point = {x: number; y: number};

type TouchState =
    | {kind: "idle"}
    /** One finger down that has neither moved nor rested long enough yet. */
    | {kind: "pending"; origin: Point; since: number}
    | {kind: "pan"; lastX: number; samples: {x: number; t: number}[]}
    | {kind: "select"}
    | {kind: "pinch"; distance: number; centre: number};

/**
 * Tells apart what fingers on the track mean: a pan, a pinch, a long press that marks
 * a range, or a tap.
 *
 * Free of the DOM and of timers, so it can be driven event by event in a test. The
 * caller feeds it pointer positions with timestamps and gets back what to do. The one
 * thing it cannot see coming — a finger held still long enough to count as a long
 * press — the caller asks about with {@link longPress} once {@link LONG_PRESS_MS} have
 * passed.
 */
export class TouchRecognizer {
    private readonly pointers = new Map<number, Point>();
    private state: TouchState = {kind: "idle"};

    /** Whether a long press is still possible, i.e. whether a timer is worth keeping. */
    get awaitingLongPress(): boolean {
        return this.state.kind === "pending";
    }

    down(id: number, x: number, y: number, t: number): TouchIntent[] {
        this.pointers.set(id, {x, y});

        if (this.pointers.size === 1) {
            this.state = {kind: "pending", origin: {x, y}, since: t};
            return [{type: "stop"}];
        }

        // A second finger turns whatever the first one was doing into a pinch. A range
        // it had started marking is finished where it stands rather than dropped.
        if (this.pointers.size === 2) {
            const ended: TouchIntent[] = this.state.kind === "select" ? [{type: "select-end"}] : [];
            this.state = {kind: "pinch", ...this.pinch()};
            return ended;
        }

        return [];
    }

    move(id: number, x: number, y: number, t: number): TouchIntent[] {
        if (!this.pointers.has(id)) return [];
        this.pointers.set(id, {x, y});

        const state = this.state;
        switch (state.kind) {
            case "pending": {
                if (Math.hypot(x - state.origin.x, y - state.origin.y) <= TOUCH_SLOP) return [];
                // Past the slop it is a pan, whichever way the finger set off: the track
                // has nothing to do vertically. The whole way from the origin is applied,
                // so the content stays under the finger instead of lagging by the slop.
                this.state = {kind: "pan", lastX: x, samples: [{x: state.origin.x, t: state.since}, {x, t}]};
                const pixels = -(x - state.origin.x);
                return pixels === 0 ? [] : [{type: "pan", pixels}];
            }
            case "pan": {
                const pixels = -(x - state.lastX);
                state.lastX = x;
                state.samples.push({x, t});
                while (state.samples.length > 2 && t - state.samples[0].t > VELOCITY_WINDOW_MS) state.samples.shift();
                return pixels === 0 ? [] : [{type: "pan", pixels}];
            }
            case "select":
                return [{type: "select-move", x}];
            case "pinch": {
                if (this.pointers.size < 2) return [];
                const next = this.pinch();
                this.state = {kind: "pinch", ...next};
                if (state.distance <= 0 || next.distance <= 0) return [];
                // Pan first, while the scale is still the one the fingers were measured
                // against, then zoom around where the centre is now: the moment that was
                // between the fingers stays between them.
                const intents: TouchIntent[] = [];
                if (next.centre !== state.centre) intents.push({type: "pan", pixels: -(next.centre - state.centre)});
                if (next.distance !== state.distance) {
                    intents.push({type: "zoom", factor: state.distance / next.distance, centre: next.centre});
                }
                return intents;
            }
            case "idle":
                return [];
        }
    }

    /**
     * Whether the finger that is down has rested long enough to start marking a range.
     * Harmless to call too early or once the moment has passed.
     */
    longPress(t: number): TouchIntent[] {
        const state = this.state;
        if (state.kind !== "pending" || t - state.since < LONG_PRESS_MS) return [];

        this.state = {kind: "select"};
        return [{type: "select-start", x: state.origin.x}];
    }

    /** A finger was lifted. */
    up(id: number, t: number): TouchIntent[] {
        return this.release(id, t, true);
    }

    /** The browser took a finger away — a gesture of its own, or the page losing focus. */
    cancel(id: number, t: number): TouchIntent[] {
        return this.release(id, t, false);
    }

    private release(id: number, t: number, lifted: boolean): TouchIntent[] {
        if (!this.pointers.delete(id)) return [];

        const state = this.state;
        if (state.kind === "pinch") {
            // The finger left over pans from where it is now, rather than jumping by
            // however far it travelled during the pinch.
            const rest = [...this.pointers.values()];
            if (rest.length === 1) this.state = {kind: "pan", lastX: rest[0].x, samples: [{x: rest[0].x, t}]};
            else if (rest.length >= 2) this.state = {kind: "pinch", ...this.pinch()};
            else this.state = {kind: "idle"};
            return [];
        }

        if (this.pointers.size > 0) return [];
        this.state = {kind: "idle"};

        switch (state.kind) {
            case "pending":
                return lifted ? [{type: "tap", x: state.origin.x}] : [];
            case "select":
                return [{type: "select-end"}];
            case "pan": {
                if (!lifted) return [];
                const velocity = velocityOf(state.samples, t);
                return Math.abs(velocity) >= MIN_FLING_VELOCITY ? [{type: "fling", velocity}] : [];
            }
            default:
                return [];
        }
    }

    private pinch(): {distance: number; centre: number} {
        const [a, b] = [...this.pointers.values()];
        return {distance: Math.hypot(a.x - b.x, a.y - b.y), centre: (a.x + b.x) / 2};
    }
}

/**
 * The finger's speed over the last {@link VELOCITY_WINDOW_MS} before [now], in pixels
 * per millisecond, positive to the right. A finger that came to rest before letting go
 * has no speed left, however fast it moved earlier.
 */
export function velocityOf(samples: {x: number; t: number}[], now: number): number {
    if (samples.length < 2) return 0;

    const last = samples[samples.length - 1];
    if (now - last.t > VELOCITY_WINDOW_MS) return 0;

    const first = samples.find((sample) => last.t - sample.t <= VELOCITY_WINDOW_MS) ?? samples[0];
    const elapsed = last.t - first.t;
    return elapsed > 0 ? (last.x - first.x) / elapsed : 0;
}

/**
 * One frame of a fling: how far the content coasts over [elapsed] milliseconds at
 * [velocity] (pixels per millisecond of finger travel), and how fast it is still going
 * afterwards. A returned velocity of 0 means the fling is over.
 *
 * The distance is what the decaying speed integrates to over the frame, so the coast
 * covers the same ground however often frames come.
 */
export function flingStep(velocity: number, elapsed: number): {pixels: number; velocity: number} {
    const decay = Math.exp(-elapsed / FLING_TIME_CONSTANT_MS);
    const travelled = velocity * FLING_TIME_CONSTANT_MS * (1 - decay);
    const next = velocity * decay;
    // The finger moving right pulls the left-hand end into view, as a pan does.
    return {pixels: -travelled, velocity: Math.abs(next) < FLING_STOP_VELOCITY ? 0 : next};
}

export function timelineGestures(node: HTMLElement, gestures: TimelineGestures) {
    let handlers = gestures;

    /** Which 0…1 position across the node something happened at. */
    const anchorOf = (clientX: number) => {
        const rect = node.getBoundingClientRect();
        if (rect.width === 0) return 0.5;
        return Math.min(Math.max((clientX - rect.left) / rect.width, 0), 1);
    };

    // Wheel deltas come in three units; only pixels can be turned into a distance.
    const wheelPixels = (delta: number, mode: number) =>
        mode === WheelEvent.DOM_DELTA_LINE ? delta * LINE_HEIGHT
            : mode === WheelEvent.DOM_DELTA_PAGE ? delta * node.clientWidth
                : delta;

    function onWheel(event: WheelEvent) {
        event.preventDefault();
        stopFling();

        // A trackpad pinch arrives as a wheel event with ctrlKey set — that, and a
        // held modifier, are the zoom gestures. Everything else scrolls sideways,
        // whichever axis the gesture came in on, so a two-finger swipe pans.
        if (event.ctrlKey || event.metaKey) {
            const spread = wheelPixels(event.deltaY, event.deltaMode);
            handlers.zoom(Math.exp(spread * ZOOM_RATE), anchorOf(event.clientX));
            return;
        }

        const delta = Math.abs(event.deltaX) > Math.abs(event.deltaY) ? event.deltaX : event.deltaY;
        handlers.pan(wheelPixels(delta, event.deltaMode));
    }

    // --- Mouse and pen ---------------------------------------------------------------

    // Where each pressed mouse or pen pointer currently is. One of them drags the
    // track, two of them pinch it.
    const pointers = new Map<number, {x: number; y: number}>();

    /** Distance and centre of the two-pointer gesture, as of the last move. */
    let pinch: {distance: number; centre: number} | null = null;

    /** Whether the one pointer on the node is currently handed to {@link TimelineDrag}. */
    let dragging = false;

    const modifiersOf = (event: PointerEvent): DragModifiers => ({
        ctrlKey: event.ctrlKey,
        metaKey: event.metaKey,
        shiftKey: event.shiftKey,
    });

    const pinchOf = (points: {x: number; y: number}[]) => ({
        distance: Math.hypot(points[0].x - points[1].x, points[0].y - points[1].y),
        centre: (points[0].x + points[1].x) / 2,
    });

    // --- Touch -----------------------------------------------------------------------

    const touch = new TouchRecognizer();

    /** What the first finger of the latest touch gesture landed on. */
    let touchTarget: EventTarget | null = null;

    let longPressTimer: ReturnType<typeof setTimeout> | null = null;

    /** The running fling's animation frame, or null while nothing coasts. */
    let flingFrame: number | null = null;

    const reducedMotion = () =>
        typeof matchMedia === "function" && matchMedia("(prefers-reduced-motion: reduce)").matches;

    function clearLongPress() {
        if (longPressTimer != null) clearTimeout(longPressTimer);
        longPressTimer = null;
    }

    function stopFling() {
        if (flingFrame != null) cancelAnimationFrame(flingFrame);
        flingFrame = null;
    }

    function startFling(velocity: number) {
        stopFling();
        if (reducedMotion() || typeof requestAnimationFrame !== "function") return;

        let current = velocity;
        let last: number | null = null;
        const frame = (now: number) => {
            if (last != null) {
                const step = flingStep(current, Math.min(now - last, 64));
                handlers.pan(step.pixels);
                current = step.velocity;
            }
            last = now;
            flingFrame = current === 0 ? null : requestAnimationFrame(frame);
        };
        flingFrame = requestAnimationFrame(frame);
    }

    function apply(intents: TouchIntent[]) {
        for (const intent of intents) {
            switch (intent.type) {
                case "stop":
                    stopFling();
                    break;
                case "pan":
                    handlers.pan(intent.pixels);
                    break;
                case "zoom":
                    handlers.zoom(intent.factor, anchorOf(intent.centre));
                    break;
                case "select-start":
                    // A short buzz where the device can, so the finger knows it has
                    // switched from moving the timeline to marking it.
                    if (handlers.drag != null) navigator.vibrate?.(10);
                    handlers.drag?.start(anchorOf(intent.x), NO_MODIFIERS, touchTarget);
                    break;
                case "select-move":
                    handlers.drag?.move(anchorOf(intent.x), NO_MODIFIERS);
                    break;
                case "select-end":
                    handlers.drag?.end();
                    break;
                case "tap":
                    if (handlers.tap != null) {
                        handlers.tap(anchorOf(intent.x), touchTarget);
                    } else if (handlers.drag != null) {
                        handlers.drag.start(anchorOf(intent.x), NO_MODIFIERS, touchTarget);
                        handlers.drag.end();
                    }
                    break;
                case "fling":
                    startFling(intent.velocity);
                    break;
            }
        }
        if (!touch.awaitingLongPress) clearLongPress();
    }

    function onTouchDown(event: PointerEvent) {
        const intents = touch.down(event.pointerId, event.clientX, event.clientY, event.timeStamp);
        if (touch.awaitingLongPress) {
            touchTarget = event.target;
            clearLongPress();
            longPressTimer = setTimeout(() => {
                longPressTimer = null;
                apply(touch.longPress(Number.POSITIVE_INFINITY));
            }, LONG_PRESS_MS);
        }
        apply(intents);
    }

    function onTouchUp(event: PointerEvent, lifted: boolean) {
        const intents = lifted ? touch.up(event.pointerId, event.timeStamp) : touch.cancel(event.pointerId, event.timeStamp);
        apply(intents);
    }

    // --- Shared listeners ------------------------------------------------------------

    function onPointerDown(event: PointerEvent) {
        const target = event.target;
        if (target instanceof Element && target.closest(`[${TIMELINE_GRIP}]`) != null) return;

        // Capturing keeps the gesture alive when a finger leaves the element, and
        // stops the browser from claiming it as a scroll or a text selection.
        node.setPointerCapture(event.pointerId);

        if (event.pointerType === "touch") {
            onTouchDown(event);
            return;
        }

        stopFling();
        pointers.set(event.pointerId, {x: event.clientX, y: event.clientY});
        pinch = pointers.size === 2 ? pinchOf([...pointers.values()]) : null;

        // A second pointer turns a drag into a pinch, so whatever the first one had
        // started is called off rather than left half-finished.
        if (pointers.size > 1) {
            if (dragging) handlers.drag?.end();
            dragging = false;
            return;
        }

        if (handlers.drag == null) return;
        dragging = true;
        handlers.drag.start(anchorOf(event.clientX), modifiersOf(event), event.target);
    }

    function onPointerMove(event: PointerEvent) {
        if (event.pointerType === "touch") {
            apply(touch.move(event.pointerId, event.clientX, event.clientY, event.timeStamp));
            return;
        }

        const previous = pointers.get(event.pointerId);
        if (previous == null) return;

        const current = {x: event.clientX, y: event.clientY};
        pointers.set(event.pointerId, current);

        if (pointers.size >= 2) {
            const next = pinchOf([...pointers.values()].slice(0, 2));
            if (pinch != null && next.distance > 0 && pinch.distance > 0) {
                // Pointers spreading apart mean less time across the same pixels.
                handlers.zoom(pinch.distance / next.distance, anchorOf(next.centre));
                handlers.pan(-(next.centre - pinch.centre));
            }
            pinch = next;
            return;
        }

        if (dragging) {
            handlers.drag?.move(anchorOf(current.x), modifiersOf(event));
            return;
        }

        // Dragging the track to the right pulls the left-hand end into view.
        handlers.pan(-(current.x - previous.x));
    }

    function release(event: PointerEvent, lifted: boolean) {
        if (event.pointerType === "touch") {
            onTouchUp(event, lifted);
            return;
        }

        pointers.delete(event.pointerId);
        if (dragging) {
            handlers.drag?.end();
            dragging = false;
        }
        // Whatever is left has moved on since the pinch started; a fresh baseline is
        // taken on the next move rather than jumping by the difference.
        pinch = null;
    }

    const onPointerUp = (event: PointerEvent) => release(event, true);
    const onPointerCancel = (event: PointerEvent) => release(event, false);

    // The wheel listener is attached by hand because it has to be non-passive: only
    // then can it keep a trackpad pinch from zooming the whole page instead.
    node.addEventListener("wheel", onWheel, {passive: false});
    node.addEventListener("pointerdown", onPointerDown);
    node.addEventListener("pointermove", onPointerMove);
    node.addEventListener("pointerup", onPointerUp);
    node.addEventListener("pointercancel", onPointerCancel);

    return {
        update(next: TimelineGestures) {
            handlers = next;
        },
        destroy() {
            clearLongPress();
            stopFling();
            node.removeEventListener("wheel", onWheel);
            node.removeEventListener("pointerdown", onPointerDown);
            node.removeEventListener("pointermove", onPointerMove);
            node.removeEventListener("pointerup", onPointerUp);
            node.removeEventListener("pointercancel", onPointerCancel);
        },
    };
}
