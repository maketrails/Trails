/** Whether the app runs on macOS (or iPadOS), where modifier keys are named differently. */
export function isMac(): boolean {
    if (typeof navigator === "undefined") return false;
    const platform = (navigator as Navigator & { userAgentData?: { platform: string } }).userAgentData?.platform
        ?? navigator.platform;
    return /mac|iphone|ipad/i.test(platform);
}

/** Label of the Option/Alt key, which shares `altKey` on every platform. */
export function altKeyLabel(): string {
    return isMac() ? "⌥" : "Alt";
}
