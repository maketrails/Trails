/**
 * What every test runs against: the DOM matchers, an IndexedDB for the history cache,
 * the English catalogue — the base language, so assertions read the texts as authored —
 * and the few browser APIs jsdom does not implement.
 */
import "@testing-library/jest-dom/vitest";
import "fake-indexeddb/auto";
import {locale, waitLocale} from "svelte-i18n";
import "$lib/i18n";

locale.set("en");
await waitLocale();

// Pointer capture is how the timeline keeps a drag alive; jsdom has no pointers to capture.
Element.prototype.setPointerCapture ??= () => {};
Element.prototype.releasePointerCapture ??= () => {};
Element.prototype.hasPointerCapture ??= () => false;

// `bind:clientWidth` is built on ResizeObserver, which jsdom lacks. Reporting each element
// once when it is observed is enough for a layout that tests fix up front.
globalThis.ResizeObserver ??= class {
    constructor(private readonly callback: ResizeObserverCallback) {}

    observe(target: Element) {
        this.callback([{target} as ResizeObserverEntry], this as unknown as ResizeObserver);
    }

    unobserve() {}

    disconnect() {}
};

// Media queries — dark mode, reduced motion — never match: the tests run in a light,
// animated world.
window.matchMedia ??= (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
});

// Svelte transitions run on the Web Animations API, which jsdom lacks. An animation
// that finishes straight away leaves every element where its transition would end.
Element.prototype.animate ??= function () {
    const animation = {
        onfinish: null as (() => void) | null,
        currentTime: 0,
        finished: Promise.resolve(),
        cancel() {},
        play() {},
        pause() {},
    };
    queueMicrotask(() => animation.onfinish?.());
    return animation as unknown as Animation;
};
