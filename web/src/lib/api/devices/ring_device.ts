import requireResponseIsFromTrails from "#lib/api/requireResponseIsFromTrails.ts";

export type RingDeviceResult =
    | { type: "success"; hasRingingStarted: boolean }
    | { type: "forbidden" }
    | { type: "timeout" }
    | { type: "error"; message: string };

async function postRing(deviceId: string, path: string): Promise<RingDeviceResult> {
    let response: Response;
    try {
        response = await fetch(`/api/v1/devices/${deviceId}/${path}`, { method: "POST" });
    } catch (e) {
        return { type: "error", message: e instanceof Error ? e.message : "Network error" };
    }
    requireResponseIsFromTrails(response);
    if (!response.ok) return { type: "error", message: `Request failed (${response.status})` };

    const data = await response.json();
    switch (data.type) {
        case "success":
            return { type: "success", hasRingingStarted: data.has_ringing_started ?? false };
        case "forbidden":
            return { type: "forbidden" };
        case "timeout":
            return { type: "timeout" };
        default:
            return { type: "error", message: data.message ?? "Unknown error" };
    }
}

/** Starts ringing one of the current user's own devices. */
export function ringDevice(deviceId: string): Promise<RingDeviceResult> {
    return postRing(deviceId, "ring");
}

/** Stops a ring previously started on one of the current user's own devices. */
export function stopRingDevice(deviceId: string): Promise<RingDeviceResult> {
    return postRing(deviceId, "ring/stop");
}
