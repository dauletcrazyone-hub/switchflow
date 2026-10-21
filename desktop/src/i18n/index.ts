import ru, { type Dictionary } from "./ru";
import en from "./en";
import kk from "./kk";

export type Lang = "ru" | "en" | "kk";
export const LANGS: Lang[] = ["ru", "en", "kk"];

export type DeepPartial<T> = T extends readonly unknown[] ? T : T extends object ? { [K in keyof T]?: DeepPartial<T[K]> } : T;

type PluralForms = { one: string; few: string; many: string; other: string };
type IsLeaf<T> = T extends string ? true : T extends readonly unknown[] ? true : T extends PluralForms ? true : false;

/** Every dotted key in the dictionary, e.g. "home.title" or "units.photos". */
export type TKey = Leaves<Dictionary>;
type Leaves<T, P extends string = ""> = {
  [K in keyof T & string]: IsLeaf<T[K]> extends true ? `${P}${K}` : Leaves<T[K], `${P}${K}.`>;
}[keyof T & string];

const dictionaries: Record<Lang, DeepPartial<Dictionary>> = { ru, en, kk };
const fallback: Record<Lang, Lang[]> = { ru: ["ru"], en: ["en", "ru"], kk: ["kk", "ru"] };

export type Params = Record<string, string | number>;

function lookup(dict: unknown, key: string): unknown {
  let cur: unknown = dict;
  for (const part of key.split(".")) {
    if (cur && typeof cur === "object" && part in cur) cur = (cur as Record<string, unknown>)[part];
    else return undefined;
  }
  return cur;
}

const pluralRules = new Map<Lang, Intl.PluralRules>();
function plural(lang: Lang, n: number): keyof PluralForms {
  let r = pluralRules.get(lang);
  if (!r) {
    r = new Intl.PluralRules(lang === "kk" ? "kk" : lang);
    pluralRules.set(lang, r);
  }
  const cat = r.select(n);
  return cat === "one" || cat === "few" || cat === "many" ? cat : "other";
}

function interpolate(s: string, params?: Params): string {
  if (!params) return s;
  return s.replace(/\{(\w+)\}/g, (m, k: string) => (k in params ? String(params[k]) : m));
}

/** Translates a key. Plural entries use `params.count`. Unknown keys return the key itself. */
export function translate(lang: Lang, key: TKey | string, params?: Params): string {
  for (const l of fallback[lang]) {
    const v = lookup(dictionaries[l], key);
    if (typeof v === "string") return interpolate(v, params);
    if (v && typeof v === "object" && !Array.isArray(v) && "other" in v) {
      const forms = v as Partial<PluralForms>;
      const n = Number(params?.count ?? 0);
      const form = forms[plural(l, n)] ?? forms.other ?? "";
      return interpolate(form, { ...params, count: formatNumber(l, n) });
    }
  }
  return key;
}

/** Raw (non-string) values such as the help Q&A arrays. */
export function translateRaw<T>(lang: Lang, key: string): T | undefined {
  for (const l of fallback[lang]) {
    const v = lookup(dictionaries[l], key);
    if (v !== undefined) return v as T;
  }
  return undefined;
}

export function hasKey(lang: Lang, key: string): boolean {
  return fallback[lang].some((l) => lookup(dictionaries[l], key) !== undefined);
}

export function formatNumber(lang: Lang, n: number, digits = 0): string {
  return new Intl.NumberFormat(lang === "kk" ? "kk-KZ" : lang === "ru" ? "ru-RU" : "en-US", {
    maximumFractionDigits: digits,
    minimumFractionDigits: 0,
  }).format(n);
}

export function resolveLang(setting: string, navigatorLang = typeof navigator !== "undefined" ? navigator.language : "ru"): Lang {
  if (setting === "ru" || setting === "en" || setting === "kk") return setting;
  const n = navigatorLang.toLowerCase();
  if (n.startsWith("kk")) return "kk";
  if (["ru", "uk", "be", "ky", "uz", "tg"].some((p) => n.startsWith(p))) return "ru";
  return "en";
}
