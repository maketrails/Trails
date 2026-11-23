import requireResponseIsFromTrails from "#lib/api/requireResponseIsFromTrails.ts";

/**
 * Device-related server calls. Keeping them here means UI components never touch
 * `fetch` directly — they depend on this repository instead.
 */
export const DeviceRepository = {
    /**
     * Sets a device's custom name. Pass `null` (or an empty string) to clear the
     * custom name, falling back to the model-derived name. Resolves `true` on
     * success, `false` on any failure (network error, forbidden, …).
     */
    async rename(deviceId: string, customName: string | null): Promise<boolean> {
        let response: Response;
        try {
            response = await fetch(`/api/v1/devices/${deviceId}`, {
                method: "PATCH",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ custom_name: customName === "" ? null : customName }),
            });
        } catch {
            return false;
        }
        requireResponseIsFromTrails(response);
        return response.ok;
    },

    /**
     * Deletes one of the current user's devices. Resolves `true` on success,
     * `false` on any failure (network error, forbidden, …).
     */
    async remove(deviceId: string): Promise<boolean> {
        let response: Response;
        try {
            response = await fetch(`/api/v1/devices/${deviceId}`, {
                method: "DELETE",
            });
        } catch {
            return false;
        }
        requireResponseIsFromTrails(response);
        return response.ok;
    },
};
