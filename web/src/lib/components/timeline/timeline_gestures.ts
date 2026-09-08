/**
 * Wheel, drag and pinch on a timeline track, as a Svelte action.
 *
 * It speaks pixels, not time: what a gesture means for the window is the caller's
 * business, which keeps this usable for any track that scrolls sideways and zooms.
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
 * Positions are 0…1 across the node, the same anchors {@link TimelineGestures.zoom}
 * speaks in.
 */
export interface TimelineDrag {
    start(anchor: number, modifiers: DragModifiers): void;
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

    // Where each pressed pointer currently is. One of them drags the track, two of
    // them pinch it; going through pointer events rather than touch events makes
    // mouse, pen and finger the same gesture.
    const pointers = new Map<number, {x: number; y: number}>();

    /** Distance and centre of the two-finger gesture, as of the last move. */
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

    function onPointerDown(event: PointerEvent) {
        const target = event.target;
        if (target instanceof Element && target.closest(`[${TIMELINE_GRIP}]`) != null) return;

        // Capturing keeps the gesture alive when a finger leaves the element, and
        // stops the browser from claiming it as a scroll or a text selection.
        node.setPointerCapture(event.pointerId);
        pointers.set(event.pointerId, {x: event.clientX, y: event.clientY});
        pinch = pointers.size === 2 ? pinchOf([...pointers.values()]) : null;

        // A second finger turns a drag into a pinch, so whatever the first one had
        // started is called off rather than left half-finished.
        if (pointers.size > 1) {
            if (dragging) handlers.drag?.end();
            dragging = false;
            return;
        }

        if (handlers.drag == null) return;
        dragging = true;
        handlers.drag.start(anchorOf(event.clientX), modifiersOf(event));
    }

    function onPointerMove(event: PointerEvent) {
        const previous = pointers.get(event.pointerId);
        if (previous == null) return;

        const current = {x: event.clientX, y: event.clientY};
        pointers.set(event.pointerId, current);

        if (pointers.size >= 2) {
            const next = pinchOf([...pointers.values()].slice(0, 2));
            if (pinch != null && next.distance > 0 && pinch.distance > 0) {
                // Fingers spreading apart mean less time across the same pixels.
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

    function onPointerUp(event: PointerEvent) {
        pointers.delete(event.pointerId);
        if (dragging) {
            handlers.drag?.end();
            dragging = false;
        }
        // Whatever is left has moved on since the pinch started; a fresh baseline is
        // taken on the next move rather than jumping by the difference.
        pinch = null;
    }

    // The wheel listener is attached by hand because it has to be non-passive: only
    // then can it keep a trackpad pinch from zooming the whole page instead.
    node.addEventListener("wheel", onWheel, {passive: false});
    node.addEventListener("pointerdown", onPointerDown);
    node.addEventListener("pointermove", onPointerMove);
    node.addEventListener("pointerup", onPointerUp);
    node.addEventListener("pointercancel", onPointerUp);

    return {
        update(next: TimelineGestures) {
            handlers = next;
        },
        destroy() {
            node.removeEventListener("wheel", onWheel);
            node.removeEventListener("pointerdown", onPointerDown);
            node.removeEventListener("pointermove", onPointerMove);
            node.removeEventListener("pointerup", onPointerUp);
            node.removeEventListener("pointercancel", onPointerUp);
        },
    };
}
