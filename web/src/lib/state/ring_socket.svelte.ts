/** Confirmed ring state for a single device, as reported by the device itself. */
type RingSocketMessage =
    | { type: "ring.state"; device_id: string; is_ringing: boolean; ringed_by: string };

/**
 * How long a connection has to stay open before it counts as established and the
 * reconnect backoff starts over. Resetting it on `open` alone would let a server
 * that accepts the upgrade and closes right away drive a reconnect every second.
 */
const STABLE_CONNECTION_MS = 10_000;

/**
 * Close codes the server sends when it refuses this socket for good (unauthenticated,
 * not the caller's device, invalid id). Retrying cannot change the answer.
 */
const PERMANENT_CLOSE_CODES = new Set([1003, 1008]);

function socketUrl(deviceId: string): string {
    const protocol = location.protocol === "https:" ? "wss:" : "ws:";
    return `${protocol}//${location.host}/api/v1/devices/${deviceId}/ring/ws`;
}

/**
 * A ring-state subscription scoped to a single device. Open it while that
 * device's detail view is on screen and close it on leave — there is no global
 * ring socket; ring state is only tracked where it is shown.
 */
export class DeviceRingSocket {
    readonly deviceId: string;

    #isRinging = $state(false);
    #ringedBy = $state<string | null>(null);

    #socket: WebSocket | null = null;
    #reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    #stableTimer: ReturnType<typeof setTimeout> | null = null;
    #reconnectAttempts = 0;
    #open = false;

    constructor(deviceId: string) {
        this.deviceId = deviceId;
    }

    get isRinging(): boolean {
        return this.#isRinging;
    }

    get ringedBy(): string | null {
        return this.#ringedBy;
    }

    open() {
        if (this.#open) return;
        this.#open = true;
        this.#reconnectAttempts = 0;
        this.#connect();
    }

    close() {
        this.#open = false;
        this.#clearReconnect();
        this.#clearStable();
        this.#reconnectAttempts = 0;
        this.#isRinging = false;
        this.#ringedBy = null;
        const ws = this.#socket;
        this.#socket = null;
        ws?.close();
    }

    #connect() {
        if (!this.#open) return;
        const existing = this.#socket;
        if (existing != null && (existing.readyState === WebSocket.OPEN || existing.readyState === WebSocket.CONNECTING)) return;

        const ws = new WebSocket(socketUrl(this.deviceId));
        this.#socket = ws;

        ws.onopen = () => {
            this.#clearStable();
            this.#stableTimer = setTimeout(() => {
                this.#stableTimer = null;
                if (this.#socket === ws) this.#reconnectAttempts = 0;
            }, STABLE_CONNECTION_MS);
        };

        ws.onmessage = (event) => {
            try {
                this.#handleMessage(JSON.parse(event.data) as RingSocketMessage);
            } catch (e) {
                console.error("Failed to parse ring socket message", e);
            }
        };

        ws.onerror = () => {
            ws.close();
        };

        ws.onclose = (event) => {
            // A socket replaced or closed on purpose must not reconnect anything.
            if (this.#socket !== ws) return;
            this.#socket = null;
            this.#clearStable();
            if (PERMANENT_CLOSE_CODES.has(event.code)) {
                console.warn(`Ring socket refused (${event.code} ${event.reason}), not reconnecting`);
                return;
            }
            this.#scheduleReconnect();
        };
    }

    #handleMessage(message: RingSocketMessage) {
        if (message.type === "ring.state" && message.device_id === this.deviceId) {
            this.#isRinging = message.is_ringing;
            this.#ringedBy = message.is_ringing ? message.ringed_by : null;
        } else {
            console.warn("Unknown ring socket message", message);
        }
    }

    #scheduleReconnect() {
        if (!this.#open) return;
        this.#clearReconnect();
        const delay = Math.min(30_000, 1_000 * 2 ** this.#reconnectAttempts);
        this.#reconnectAttempts++;
        this.#reconnectTimer = setTimeout(() => this.#connect(), delay);
    }

    #clearStable() {
        if (this.#stableTimer != null) {
            clearTimeout(this.#stableTimer);
            this.#stableTimer = null;
        }
    }

    #clearReconnect() {
        if (this.#reconnectTimer != null) {
            clearTimeout(this.#reconnectTimer);
            this.#reconnectTimer = null;
        }
    }
}
