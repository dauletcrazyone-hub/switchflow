import { afterEach, describe, expect, it, vi } from "vitest";
import { DemoBackend } from "./demo";
import { compatRule } from "../lib/compat";
import type { Snapshot } from "./types";

describe("compatibility table (shared with Rust)", () => {
  it("is honest about platform limits", () => {
    expect(compatRule("messages", "ios", "android").status).toBe("official_tool");
    expect(compatRule("music", "ios", "android").status).toBe("unsupported");
    expect(compatRule("photos", "android", "ios").status).toBe("supported");
    expect(compatRule("documents", "android", "ios").status).toBe("action_required");
    // Backup to PC: anything readable, except iOS music.
    expect(compatRule("music", "ios", null).status).toBe("unsupported");
    expect(compatRule("videos", "ios", null).status).toBe("supported");
  });
});

describe("demo backend", () => {
  afterEach(() => vi.useRealTimers());

  it("pairs two demo phones and runs a full transfer", async () => {
    vi.useFakeTimers();
    const demo = new DemoBackend({ timeScale: 0.01 });
    let snap: Snapshot = await demo.getSnapshot();
    demo.onSnapshot((s) => (snap = s));
    const finished = vi.fn();
    demo.onSessionFinished(finished);

    // Demo mode starts empty: no phone appears until the user opens pairing.
    expect(snap.devices).toHaveLength(0);
    await demo.startPairing();
    await vi.advanceTimersByTimeAsync(50);
    expect(snap.pairRequests).toHaveLength(1);
    await demo.respondPairing(snap.pairRequests[0]!.id, true);
    await vi.advanceTimersByTimeAsync(50);
    await demo.respondPairing(snap.pairRequests[0]!.id, true);
    expect(snap.devices).toHaveLength(2);
    expect(snap.sourceId).toBe("demo-android");
    expect(snap.targetId).toBe("demo-iphone");

    await demo.requestScan(["photos", "contacts", "messages"]);
    await vi.advanceTimersByTimeAsync(100);
    const src = snap.devices.find((d) => d.info.id === snap.sourceId)!;
    // Messages are never scanned: they are not transferable.
    expect(src.inventory.map((i) => i.category).sort()).toEqual(["contacts", "photos"]);

    await demo.startSession({ kind: "transfer", selections: [{ category: "photos", albumIds: ["scr"] }, { category: "contacts", albumIds: null }] });
    expect(snap.session?.state).toBe("preparing");
    // Preflight finishes after ~26 ms at this time scale; the transfer itself takes longer.
    await vi.advanceTimersByTimeAsync(27);
    expect(snap.session?.state).toBe("running");
    await vi.advanceTimersByTimeAsync(2000);
    expect(snap.session?.state).toBe("completed");
    expect(snap.session?.totals.failedItems).toBe(3);
    expect(finished).toHaveBeenCalledOnce();

    expect(await demo.retryFailed()).toBe(3);
    expect(snap.session?.totals.failedItems).toBe(0);
    await demo.dismissSession();
    expect(snap.session).toBeNull();
    expect((await demo.getHistory())[0]?.outcome).toBe("partial");
    demo.dispose();
  });

  it("can pause and cancel", async () => {
    vi.useFakeTimers();
    const demo = new DemoBackend({ timeScale: 0.01 });
    let snap: Snapshot = await demo.getSnapshot();
    demo.onSnapshot((s) => (snap = s));
    await demo.startPairing();
    await vi.advanceTimersByTimeAsync(50);
    await demo.respondPairing(snap.pairRequests[0]!.id, true);
    await vi.advanceTimersByTimeAsync(50);
    await demo.respondPairing(snap.pairRequests[0]!.id, true);
    await demo.requestScan(["videos"]);
    await vi.advanceTimersByTimeAsync(50);
    await demo.startSession({ kind: "transfer", selections: [{ category: "videos", albumIds: null }] });
    await vi.advanceTimersByTimeAsync(100);
    await demo.pauseSession();
    expect(snap.session?.state).toBe("paused");
    await demo.resumeSession();
    await demo.cancelSession();
    expect(snap.session?.state).toBe("cancelled");
    demo.dispose();
  });
});
