import {afterEach, beforeEach, describe, expect, it, vi} from "vitest";
import {flushSync} from "svelte";
import {render} from "@testing-library/svelte";
import DeviceActionsHarness from "./DeviceActionsHarness.svelte";

/** Counts the ring sockets the panel opens; none of them ever connects. */
class FakeWebSocket {
    static readonly CONNECTING = 0;
    static readonly OPEN = 1;
    static readonly CLOSED = 3;
    static urls: string[] = [];
    readyState = FakeWebSocket.CONNECTING;
    onopen = null;
    onmessage = null;
    onerror = null;
    onclose = null;

    constructor(url: string) {
        FakeWebSocket.urls.push(url);
    }

    close() {
        this.readyState = FakeWebSocket.CLOSED;
    }
}

describe("DeviceActions", () => {
    beforeEach(() => {
        FakeWebSocket.urls = [];
        vi.stubGlobal("WebSocket", FakeWebSocket);
    });

    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it("keeps its ring socket while updates replace the device object", () => {
        const {rerender} = render(DeviceActionsHarness, {device: {id: "a", battery: 50}});
        flushSync();
        expect(FakeWebSocket.urls).toHaveLength(1);

        for (let battery = 51; battery < 55; battery++) {
            rerender({device: {id: "a", battery}});
            flushSync();
        }
        expect(FakeWebSocket.urls).toHaveLength(1);
    });

    it("opens a new ring socket for another device", () => {
        const {rerender} = render(DeviceActionsHarness, {device: {id: "a", battery: 50}});
        flushSync();
        rerender({device: {id: "b", battery: 50}});
        flushSync();
        expect(FakeWebSocket.urls).toHaveLength(2);
        expect(FakeWebSocket.urls[1]).toContain("/devices/b/ring/ws");
    });
});
