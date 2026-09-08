/**
 * A timeline is four parts, each usable on its own: a window model that owns where
 * the view is, a gesture action that drives it, an axis that draws the calendar
 * behind it, and a scrollbar that does both. [Timeline] is one arrangement of them.
 */
export {default as Timeline} from "./Timeline.svelte";
export {default as TimelineAxis} from "./TimelineAxis.svelte";
export {default as TimelineScrollbar} from "./TimelineScrollbar.svelte";
export {createTimelineWindow, type TimelineView, type TimelineWindow, type TimelineWindowOptions} from "./timeline_window";
export {timelineGestures, type TimelineGestures} from "./timeline_gestures";
export {
    axisFor,
    calendarFor,
    weekStartOf,
    SCALES,
    type Axis,
    type AxisOptions,
    type AxisSeparator,
    type AxisTick,
    type Calendar,
    type Scale,
    type Unit,
} from "./timeline_scale";
