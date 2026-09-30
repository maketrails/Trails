import {describe, expect, it} from "vitest";
import {pickedRange, pickValue, TIMELINE_PICK} from "./timeline_selection";

function element(value?: string): HTMLElement {
    const node = document.createElement("div");
    if (value != null) node.setAttribute(TIMELINE_PICK, value);
    return node;
}

describe("pickedRange", () => {
    it("reads the stretch an element stands for", () => {
        const range = pickedRange(element(pickValue(1_000, 5_000)));

        expect(range?.start.getTime()).toBe(1_000);
        expect(range?.end.getTime()).toBe(5_000);
    });

    it("finds it on an ancestor, for a press that landed on a label inside", () => {
        const block = element(pickValue(1_000, 5_000));
        const label = document.createElement("span");
        block.append(label);

        expect(pickedRange(label)?.end.getTime()).toBe(5_000);
    });

    it("finds nothing where nothing was marked, or something broken was", () => {
        expect(pickedRange(element())).toBeNull();
        expect(pickedRange(element("nonsense"))).toBeNull();
        expect(pickedRange(element(pickValue(5_000, 1_000)))).toBeNull();
        expect(pickedRange(null)).toBeNull();
    });
});
