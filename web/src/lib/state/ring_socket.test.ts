import {afterEach, beforeEach, describe, expect, it, vi} from "vitest";
import {DeviceRingSocket} from "./ring_socket.svelte";

/** A stand-in for the browser socket that the test opens and closes by hand. */
class FakeWebSocket {
    static readonly CONNECTING = 0;
    static readonly OPEN = 1;
    static readonly CLOSING = 2;
    static readonly CLOSED = 3;
    static instances: FakeWebSocket[] = [];

    readyState = FakeWebSocket.CONNECTING;
    onopen: (() => void) | null = null;
    onmessage: ((event: {data: string}) => void) | null = null;
    onerror: (() => void) | null = null;
    onclose: ((event: {code: number; reason: string}) => void) | null = null;

    constructor(readonly url: string) {
        FakeWebSocket.instances.push(this);
    }

    serverOpen() {
        this.readyState = FakeWebSocket.OPEN;
        this.onopen?.();
    }

    serverClose(code = 1006, reason = "") {
        this.readyState = FakeWebSocket.CLOSED;
        this.onclose?.({code, reason});
    }

    close() {
        if (this.readyState === FakeWebSocket.CLOSED) return;
        this.serverClose(1000);
    }
}

describe("DeviceRingSocket", () => {
    beforeEach(() => {
        vi.useFakeTimers();
        FakeWebSocket.instances = [];
        vi.stubGlobal("WebSocket", FakeWebSocket);
        vi.spyOn(console, "warn").mockImplementation(() => {});
    });

    afterEach(() => {
        vi.useRealTimers();
        vi.unstubAllGlobals();
        vi.restoreAllMocks();
    });

    it("backs off when the server accepts and closes right away", () => {
        const socket = new DeviceRingSocket("device");
        socket.open();

        // Five rounds of accept-then-close: each wait must be longer than the last
        // instead of starting over at one second.
        let waited = 0;
        for (let round = 0; round < 5; round++) {
            const ws = FakeWebSocket.instances.at(-1)!;
            ws.serverOpen();
            ws.serverClose();
            const before = FakeWebSocket.instances.length;
            const delay = 1_000 * 2 ** round;
            vi.advanceTimersByTime(delay - 1);
            expect(FakeWebSocket.instances.length).toBe(before);
            vi.advanceTimersByTime(1);
            expect(FakeWebSocket.instances.length).toBe(before + 1);
            waited += delay;
        }
        expect(waited).toBe(31_000);
        socket.close();
    });

    it("starts the backoff over once a connection stayed open", () => {
        const socket = new DeviceRingSocket("device");
        socket.open();
        FakeWebSocket.instances[0].serverClose();
        vi.advanceTimersByTime(1_000);
        FakeWebSocket.instances[1].serverClose();
        vi.advanceTimersByTime(2_000);

        const ws = FakeWebSocket.instances[2];
        ws.serverOpen();
        vi.advanceTimersByTime(10_000);
        ws.serverClose();
        vi.advanceTimersByTime(1_000);
        expect(FakeWebSocket.instances.length).toBe(4);
        socket.close();
    });

    it("does not reconnect when the server refuses the socket", () => {
        const socket = new DeviceRingSocket("device");
        socket.open();
        const ws = FakeWebSocket.instances[0];
        ws.serverOpen();
        ws.serverClose(1008, "Forbidden");
        vi.advanceTimersByTime(60_000);
        expect(FakeWebSocket.instances.length).toBe(1);
        socket.close();
    });

    it("does not reconnect after being closed", () => {
        const socket = new DeviceRingSocket("device");
        socket.open();
        FakeWebSocket.instances[0].serverOpen();
        socket.close();
        vi.advanceTimersByTime(60_000);
        expect(FakeWebSocket.instances.length).toBe(1);
    });
});
