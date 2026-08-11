import requireResponseIsFromTrails from "$lib/api/requireResponseIsFromTrails";

/** How far the track of one of the user's own devices has been optimized. */
export interface DeviceOptimization {
    optimized_points: number;
    unoptimized_points: number;
    /** The whole measured series — overlaps the two above, for comparison. */
    raw_points: number;
    optimized_distance_meters: number;
    unoptimized_distance_meters: number;
    raw_distance_meters: number;
    /** Share of the settled positions that are optimized, 0..1. */
    progress: number;
    is_running: boolean;
}

export type DeviceOptimizationResult =
    | { type: "success"; optimization: DeviceOptimization }
    | { type: "forbidden" }
    | { type: "error"; message: string };

/** Reads the optimization state of one of the user's own devices. */
export async function fetchDeviceOptimization(deviceId: string): Promise<DeviceOptimizationResult> {
    let response: Response;
    try {
        response = await fetch(`/api/v1/devices/${deviceId}/optimization`);
    } catch (e) {
        return {type: "error", message: e instanceof Error ? e.message : "Network error"};
    }
    requireResponseIsFromTrails(response);
    if (response.status === 403) return {type: "forbidden"};
    if (!response.ok) return {type: "error", message: `Request failed (${response.status})`};

    return {type: "success", optimization: (await response.json()) as DeviceOptimization};
}

export type ReoptimizeResult = {type: "accepted"} | {type: "forbidden"} | {type: "error"; message: string};

/**
 * Asks the server to throw the optimized track away and derive it again. The
 * answer only says the run was started — it may take minutes, and the progress
 * arrives over the webapp socket.
 */
export async function reoptimizeDevice(deviceId: string): Promise<ReoptimizeResult> {
    let response: Response;
    try {
        response = await fetch(`/api/v1/devices/${deviceId}/optimization/reoptimize`, {method: "POST"});
    } catch (e) {
        return {type: "error", message: e instanceof Error ? e.message : "Network error"};
    }
    requireResponseIsFromTrails(response);
    if (response.status === 403) return {type: "forbidden"};
    if (!response.ok) return {type: "error", message: `Request failed (${response.status})`};

    return {type: "accepted"};
}
