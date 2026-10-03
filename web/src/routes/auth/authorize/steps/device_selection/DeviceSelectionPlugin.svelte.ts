import type {DeviceOption, DeviceSelectState} from "./types.ts";
import type {FlowState} from "@julius-babies/authentikt-svelte";

interface AuthentiktClient {
    readonly currentFlow: FlowState | null;
    readonly sessionUrl: URL;
    updateState: () => Promise<void>;
}

export type NewDeviceNameResult = "success" | "unknown_error" | "name_already_exists"

export class DeviceSelectionPlugin {
    private _state = $state<DeviceSelectState>(null);
    private readonly _ns: string;
    private readonly authentikt: AuthentiktClient;

    constructor(authentikt: AuthentiktClient, namespace: string) {
        this.authentikt = authentikt;
        this._ns = namespace;
    }

    get namespace() {
        return this._ns;
    }

    get isActive() {
        return this.authentikt.currentFlow?.step?.type === "step" &&
            this.authentikt.currentFlow.step.namespace === this._ns;
    }

    get data(): DeviceSelectState {
        if (this._state) return this._state;
        const step = this.authentikt.currentFlow?.step;
        if (step?.type === "step" && step.payload?.options) {
            return {options: step.payload.options as DeviceOption[]};
        }
        return null;
    }

    selectDevice = async (deviceId: string) => {
        try {
            const url = new URL(`steps/plugins/${this._ns}/select`, this.authentikt.sessionUrl);
            const response = await fetch(url.toString(), {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({device_id: deviceId}),
            });
            const data = await response.json();
            if (data.success === true) {
                await this.authentikt.updateState();
            }
        } catch (e) {
            console.error(e);
        }
    };

    newDevice = async (deviceName: string): Promise<NewDeviceNameResult> => {
        try {
            const url = new URL(`steps/plugins/${this._ns}/new-device`, this.authentikt.sessionUrl);
            const response = await fetch(url.toString(), {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({name: deviceName}),
            });
            await this.authentikt.updateState();
            const data = await response.json();
            if (data.success) {
                return "success";
            }
            if (data.error === "name_already_exists") return "name_already_exists";
            return "success";
        } catch (e) {
            console.error(e);
            return "unknown_error";
        }
    };
}