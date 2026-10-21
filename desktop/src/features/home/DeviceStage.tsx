import { AnimatePresence } from "motion/react";
import type { DeviceView, Endpoint } from "../../backend/types";
import { DeviceCard, EmptySlot, EndpointCard } from "../../components/device/DeviceCard";
import { FlowBridge } from "../../components/device/FlowBridge";
import { useApp, useT } from "../../state/store";

export function useSlots() {
  const snapshot = useApp((s) => s.snapshot);
  const find = (id: string | null) => (id ? snapshot.devices.find((d) => d.info.id === id) : undefined);
  return { source: find(snapshot.sourceId), target: find(snapshot.targetId) };
}

/** Two phones and the bridge between them (setup state). */
export function DeviceStage() {
  const { source, target } = useSlots();
  const run = useApp((s) => s.run);
  const openPairing = useApp((s) => s.openPairing);
  return (
    <div className="flex items-stretch gap-0">
      <AnimatePresence mode="popLayout">
        {source ? <DeviceCard key={source.info.id} device={source} role="source" /> : <EmptySlot key="e1" index={1} onConnect={openPairing} />}
      </AnimatePresence>
      <FlowBridge
        connected={!!source && !!target && source.connected && target.connected}
        active={false}
        onSwap={() => void run((b) => b.swapRoles())}
        swapDisabled={!source && !target}
      />
      <AnimatePresence mode="popLayout">
        {target ? <DeviceCard key={target.info.id} device={target} role="target" /> : <EmptySlot key="e2" index={2} onConnect={openPairing} />}
      </AnimatePresence>
    </div>
  );
}

/** Renders either a phone card or a PC/backup card for a session endpoint. */
export function EndpointView({ endpoint, role, devices, active }: { endpoint: Endpoint; role: "source" | "target"; devices: DeviceView[]; active?: boolean }) {
  const t = useT();
  if (endpoint.type === "device") {
    const d = devices.find((x) => x.info.id === endpoint.id);
    if (d) return <DeviceCard device={d} role={role} compact active={active} />;
    return <EndpointCard title={endpoint.name} subtitle={endpoint.model} role={role} compact />;
  }
  if (endpoint.type === "folder") return <EndpointCard title={t("home.thisPc")} subtitle={endpoint.path} role={role} compact />;
  return <EndpointCard title={endpoint.name} subtitle={endpoint.path} role={role} compact />;
}
