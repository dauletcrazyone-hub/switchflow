import type { DeepPartial } from "./index";
import type { Dictionary } from "./ru";

// Kazakh (beta). Missing keys fall back to Russian. To complete the translation,
// copy keys from ru.ts — the type system checks the shape.
const kk: DeepPartial<Dictionary> = {
  app: {
    slogan: "Деректеріңіз. Құрылғыларыңыз. Бір жеңіл көшу.",
    demoBadge: "Демо",
    demoBanner: "Демо режимі: құрылғылар, файлдар мен барысы ойдан шығарылған. Нақты телефондар қолданылмайды.",
  },
  nav: {
    home: "Басты бет",
    history: "Тарих",
    backups: "Сақтық көшірмелер",
    settings: "Баптаулар",
    help: "Көмек",
    about: "Бағдарлама туралы",
  },
  window: { minimize: "Жию", maximize: "Үлкейту", restore: "Қалпына келтіру", close: "Жабу" },
  privacy: { badge: "Жергілікті · Шифрланған" },
  home: {
    title: "Деректерді көшіру",
    subtitle: "Екі телефонды қосып, деректерді таңдап, «Көшіруді бастау» түймесін басыңыз.",
    connectFirst: "Бірінші телефонды қосыңыз",
    connectSecond: "Екінші телефонды қосыңыз",
    connectHint: "Телефонда SwitchFlow қолданбасын ашып, QR-кодты сканерлеңіз",
    unlockHint: "Құрылғының құлпын ашып, рұқсат беріңіз",
    showQr: "QR-кодты көрсету",
    source: "Дереккөз",
    target: "Қабылдаушы",
    swap: "Бағытты ауыстыру",
  },
  categories: {
    photos: { title: "Фотосуреттер", desc: "Түпнұсқа сапа, альбомдар, түсірілген күні" },
    videos: { title: "Бейнелер", desc: "Түпнұсқа пішім, үлкен файлдар" },
    contacts: { title: "Контактілер", desc: "Аттар, нөмірлер, пошта · vCard" },
    documents: { title: "Құжаттар", desc: "PDF, DOCX, XLSX, TXT және басқа файлдар" },
    music: { title: "Музыка", desc: "Аудиофайлдар және олардың атаулары" },
    calendars: { title: "Күнтізбелер", desc: "Оқиғалар, уақыт пен күндер" },
  },
  select: {
    title: "Не көшіру керек",
    selectAll: "Барлық қолжетімдіні таңдау",
    start: "Көшіруді бастау",
  },
  transfer: {
    running: "Көшіру жүріп жатыр",
    pause: "Кідірту",
    resume: "Жалғастыру",
    cancel: "Бас тарту",
  },
  report: { completed: "Көшіру аяқталды", done: "Дайын", retry: "Қайталау" },
  common: { cancel: "Бас тарту", close: "Жабу", confirm: "Растау", retry: "Қайталау" },
};

export default kk;
