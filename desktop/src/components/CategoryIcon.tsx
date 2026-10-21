import {
  AppWindow,
  CalendarDays,
  FileText,
  Image,
  MessageCircle,
  MessageSquareText,
  Music2,
  PhoneCall,
  SlidersHorizontal,
  UsersRound,
  Video,
  type LucideIcon,
} from "lucide-react";
import type { Category } from "../backend/types";

export const CATEGORY_ICONS: Record<Category, LucideIcon> = {
  photos: Image,
  videos: Video,
  contacts: UsersRound,
  documents: FileText,
  music: Music2,
  calendars: CalendarDays,
  messages: MessageSquareText,
  callHistory: PhoneCall,
  settings: SlidersHorizontal,
  appData: AppWindow,
  whatsapp: MessageCircle,
};

// Each category gets its own calm tint so the grid scans quickly.
const TINTS: Record<Category, string> = {
  photos: "#478BFF",
  videos: "#8B7BFF",
  contacts: "#35C98A",
  documents: "#F2B450",
  music: "#FF7AA8",
  calendars: "#FF8A5B",
  messages: "#75C8FF",
  callHistory: "#35C98A",
  settings: "#9CAAC0",
  appData: "#9CAAC0",
  whatsapp: "#35C98A",
};

export function CategoryIcon({ category, size = 40, muted }: { category: Category; size?: number; muted?: boolean }) {
  const Icon = CATEGORY_ICONS[category];
  const tint = muted ? "#8A94A6" : TINTS[category];
  return (
    <div
      className="flex shrink-0 items-center justify-center rounded-[12px]"
      style={{ width: size, height: size, background: `${tint}1F`, color: tint }}
      aria-hidden
    >
      <Icon size={Math.round(size * 0.5)} strokeWidth={1.9} />
    </div>
  );
}
