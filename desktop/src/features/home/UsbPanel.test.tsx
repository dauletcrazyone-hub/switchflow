import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { EMPTY_SNAPSHOT } from "../../backend/types";
import { useApp } from "../../state/store";
import { UsbPanel } from "./UsbPanel";

afterEach(cleanup);

describe("UsbPanel", () => {
  // Found on a Galaxy S22 that hung half-switched (Windows code 43): UsbDk could list no device at
  // all, so the A10 next to it could not connect either and the panel just disappeared.
  it("says a stuck phone blocks the cable even when no phone can be listed", () => {
    useApp.setState({ lang: "ru", snapshot: { ...EMPTY_SNAPSHOT, cable: { available: true, links: 0, phones: 0, stuck: true } } });
    render(<UsbPanel />);
    expect(screen.getByText("Телефон завис на USB")).toBeTruthy();
    expect(screen.getByText(/Отключите кабели телефонов/)).toBeTruthy();
  });

  it("shows nothing when no phone is on USB", () => {
    useApp.setState({ lang: "ru", snapshot: { ...EMPTY_SNAPSHOT, cable: { available: true, links: 0, phones: 0, stuck: false } } });
    const { container } = render(<UsbPanel />);
    expect(container.innerHTML).toBe("");
  });
});
