/** How far the optimizer has got on one of the user's own devices. */
export interface OptimizationProgress {
    /** Share of the settled positions that are optimized, 0..1. */
    progress: number;
    is_running: boolean;
}

type OptimizationSocketMessage = {
    type: "optimization.progress";
    device_id: string;
    progress: number;
    is_running: boolean;
};

function socketUrl(): string {
    const protocol = location.protocol === "https:" ? "wss:" : "ws:";
    return `${protocol}//${location.host}/api/v1/webapp/optimization/ws`;
}

/**
 * Optimization progress for the user's own devices, on its own socket.
 *
 * Deliberately not part of the device-update socket: progress arrives after every
 * batch of positions, while a device update re-sends the whole device list. A view
 * that does not show the optimization simply never opens this.
 *
 * Reports change only — there is no initial snapshot. The numbers come from
 * `GET /devices/{deviceId}/optimization`; this keeps them moving.
 */
export class OptimizationSocket {
    #progress = $state<Record<string, OptimizationProgress>>({});

    #socket: WebSocket | null = null;
    #reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    #reconnectAttempts = 0;
    #open = false;

    /** Progress per device id, as far as it has been reported. */
    get progress(): Record<string, OptimizationProgress> {
        return this.#progress;
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
        this.#reconnectAttempts = 0;
        const ws = this.#socket;
        this.#socket = null;
        ws?.close();
    }

    #clearReconnect() {
        if (this.#reconnectTimer != null) {
            clearTimeout(this.#reconnectTimer);
            this.#reconnectTimer = null;
        }
    }

    #scheduleReconnect() {
        if (!this.#open) return;
        this.#clearReconnect();
        const delay = Math.min(30_000, 1_000 * 2 ** this.#reconnectAttempts);
        this.#reconnectAttempts++;
        this.#reconnectTimer = setTimeout(() => this.#connect(), delay);
    }

    #connect() {
        if (!this.#open) return;

        const existing = this.#socket;
        if (existing != null && (existing.readyState === WebSocket.OPEN || existing.readyState === WebSocket.CONNECTING)) {
            return;
        }

        const ws = new WebSocket(socketUrl());
        this.#socket = ws;

        ws.onopen = () => {
            this.#reconnectAttempts = 0;
        };

        ws.onmessage = (event) => {
            let message: OptimizationSocketMessage;
            try {
                message = JSON.parse(event.data) as OptimizationSocketMessage;
            } catch (e) {
                console.error("Failed to parse optimization socket message", e);
                return;
            }

            if (message.type !== "optimization.progress") {
                console.warn("Unknown optimization socket message", message);
                return;
            }

            // Replaced rather than mutated so readers of the record react.
            this.#progress = {
                ...this.#progress,
                [message.device_id]: {progress: message.progress, is_running: message.is_running},
            };
        };

        ws.onerror = () => ws.close();

        ws.onclose = () => {
            if (this.#socket === ws) this.#socket = null;
            this.#scheduleReconnect();
        };
    }
}
