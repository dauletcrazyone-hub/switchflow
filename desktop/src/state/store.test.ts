import { beforeEach, describe, expect, it } from "vitest";
import { EMPTY_SNAPSHOT } from "../backend/types";
import { applySnapshot, useApp } from "./store";

describe("album choice follows the source phone", () => {
  beforeEach(() => {
    useApp.setState({
      snapshot: { ...EMPTY_SNAPSHOT, sourceId: "s22", targetId: "a10" },
      selection: { categories: ["photos"], albums: { photos: ["s22-test-album"] }, contactsPolicy: "merge", includePrevious: false },
    });
  });

  it("keeps the choice while the source stays the same", () => {
    applySnapshot({ ...EMPTY_SNAPSHOT, sourceId: "s22", targetId: "a10" });
    expect(useApp.getState().selection.albums).toEqual({ photos: ["s22-test-album"] });
  });

  // Found on a Galaxy S22 ↔ Galaxy A10 test: after "swap direction" the S22 album id matched none
  // of the A10 albums, so photos stayed ticked but 0 photos were selected.
  it("drops the choice after a direction swap, keeping the categories", () => {
    applySnapshot({ ...EMPTY_SNAPSHOT, sourceId: "a10", targetId: "s22" });
    const sel = useApp.getState().selection;
    expect(sel.albums).toEqual({});
    expect(sel.categories).toEqual(["photos"]);
  });
});
