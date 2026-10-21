import { motion } from "motion/react";
import { Check } from "lucide-react";
import { useEffect, useState } from "react";
import { Logo } from "../../components/Logo";
import { Card } from "../../components/ui/Controls";
import { motionTokens } from "../../lib/ui";
import { useApp, useT } from "../../state/store";

const COMPONENTS: Array<[string, string]> = [
  ["Tauri", "MIT / Apache-2.0"],
  ["React", "MIT"],
  ["Motion", "MIT"],
  ["Lucide", "ISC"],
  ["Tailwind CSS", "MIT"],
  ["Zustand", "MIT"],
  ["Tokio", "MIT"],
  ["axum / axum-server", "MIT"],
  ["rustls / ring", "Apache-2.0 / ISC / MIT"],
  ["rcgen", "MIT / Apache-2.0"],
  ["RustCrypto sha2", "MIT / Apache-2.0"],
  ["qrcode", "MIT / Apache-2.0"],
  ["windows-rs", "MIT / Apache-2.0"],
  ["Segoe UI Variable (Windows)", "System font"],
];

export function AboutScreen() {
  const t = useT();
  const backend = useApp((s) => s.backend);
  const [version, setVersion] = useState("0.1.0");
  useEffect(() => {
    void backend.getAppInfo().then((i) => setVersion(i.version)).catch(() => undefined);
  }, [backend]);

  return (
    <div className="mx-auto max-w-[680px] pb-10">
      <div className="flex flex-col items-center pt-6 text-center">
        <motion.div initial={{ scale: 0.8, opacity: 0, rotate: -6 }} animate={{ scale: 1, opacity: 1, rotate: 0 }} transition={motionTokens.springSoft}>
          <Logo size={96} glow />
        </motion.div>
        <h1 className="mt-5 font-display text-display font-semibold">SwitchFlow</h1>
        <p className="mt-1 text-body text-text-2">{t("app.slogan")}</p>
        <p className="tabular mt-1 text-caption text-text-3">{t("about.version", { version })}</p>
      </div>

      <Card className="mt-8 p-5">
        <h2 className="text-headline font-semibold">{t("about.promise")}</h2>
        <ul className="mt-3 grid grid-cols-2 gap-3">
          {["p1", "p2", "p3", "p4"].map((k) => (
            <li key={k} className="flex items-start gap-2 text-callout">
              <Check size={16} className="mt-0.5 shrink-0 text-success" /> {t(`about.${k}`)}
            </li>
          ))}
        </ul>
      </Card>

      <Card className="mt-4 p-5">
        <h2 className="text-headline font-semibold">{t("about.privacy")}</h2>
        <p className="selectable mt-2 text-callout leading-relaxed text-text-2">{t("about.privacyText")}</p>
      </Card>

      <Card className="mt-4 p-5">
        <h2 className="text-headline font-semibold">{t("about.licenses")}</h2>
        <p className="mt-1 text-caption text-text-2">{t("about.licensesHint")}</p>
        <ul className="mt-3 grid grid-cols-2 gap-x-6 gap-y-1.5">
          {COMPONENTS.map(([name, lic]) => (
            <li key={name} className="flex justify-between gap-3 text-callout">
              <span>{name}</span>
              <span className="text-text-3">{lic}</span>
            </li>
          ))}
        </ul>
      </Card>
    </div>
  );
}
