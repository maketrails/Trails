import requireResponseIsFromTrails from "#lib/api/requireResponseIsFromTrails.ts";

export type PingDeviceResult =
    | { type: "success"; hasDeliveredNotification: boolean }
    | { type: "forbidden" }
    | { type: "timeout" }
    | { type: "error"; message: string };

/**
 * Pings one of the current user's own devices and resolves once the device has
 * acknowledged it (or the server times out after ~5s). Only works for devices
 * owned by the signed-in user; the server answers `forbidden` otherwise.
 */
export async function pingDevice(deviceId: string): Promise<PingDeviceResult> {
    let response: Response;
    try {
        response = await fetch(`/api/v1/devices/${deviceId}/ping`, { method: "POST" });
    } catch (e) {
        return { type: "error", message: e instanceof Error ? e.message : "Network error" };
    }
    requireResponseIsFromTrails(response);
    if (!response.ok) return { type: "error", message: `Request failed (${response.status})` };

    const data = await response.json();
    switch (data.type) {
        case "success":
            return { type: "success", hasDeliveredNotification: data.has_delivered_notification ?? false };
        case "forbidden":
            return { type: "forbidden" };
        case "timeout":
            return { type: "timeout" };
        default:
            return { type: "error", message: data.message ?? "Unknown error" };
    }
}
