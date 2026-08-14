import {webappSocket} from "$lib/state/webapp_socket.svelte";
import {foreignShares} from "$lib/state/share_socket.svelte";

/**
 * Whether the thing drawn under [id] is reachable right now — an own device by its
 * device id, a share of either origin by its active-share id.
 *
 * All three sources in one lookup, because the places that need it (a map pin, a
 * list row) are handed an id and do not care which of them it came from.
 *
 * Reactive: it reads the socket state, so a caller inside a `$derived` follows a
 * device going offline. That is what a map pin needs — a pin is mounted imperatively
 * and only ever moved afterwards, so a prop would keep whatever it was mounted with.
 *
 * An id nothing is known about counts as online: a target on its way out should not
 * turn grey for the moment between losing its data and losing its pin.
 */
export function isTargetOnline(id: string): boolean {
    const device = webappSocket.devices.find((device) => device.id === id);
    if (device != null) return device.isOnline;

    const share = webappSocket.shares.find((share) => share.id === id);
    if (share != null) return share.is_online;

    // A foreign homeserver that does not report presence is read as online — see
    // ShareSnapshot.is_online.
    const snapshot = foreignShares.entries.find((entry) => entry.activeShareId === id)
        ?.subscription.snapshot;
    if (snapshot != null) return snapshot.is_online ?? true;

    return true;
}
