import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useApp } from "../../state/store";
import { CategoryCard } from "./CategoryCard";

beforeEach(() => useApp.setState({ lang: "ru" }));
afterEach(cleanup);

describe("CategoryCard", () => {
  it("shows counts and toggles a supported category", () => {
    const onToggle = vi.fn();
    render(
      <CategoryCard
        category="photos"
        status="supported"
        note={null}
        inventory={{ category: "photos", permission: "granted", count: 1248, totalBytes: 6.4 * 1024 ** 3, albums: [], note: null, scannedMs: Date.now() }}
        scanning={false}
        selected={false}
        onToggle={onToggle}
        onRescan={() => undefined}
      />,
    );
    expect(screen.getByText("Фотографии")).toBeTruthy();
    expect(screen.getByText(/1.248 фото · 6,4.ГБ/)).toBeTruthy();
    fireEvent.click(screen.getByRole("checkbox"));
    expect(onToggle).toHaveBeenCalledWith(true);
  });

  it("never lets an official-tool category be selected", () => {
    const onToggle = vi.fn();
    render(
      <CategoryCard
        category="messages"
        status="official_tool"
        note="compat.official.moveToIos"
        inventory={undefined}
        scanning={false}
        selected={false}
        onToggle={onToggle}
        onRescan={() => undefined}
      />,
    );
    const box = screen.getByRole("checkbox") as HTMLButtonElement;
    expect(box.disabled).toBe(true);
    expect(screen.getByText("Доступен перенос через официальный инструмент")).toBeTruthy();
  });

  it("explains denied access and offers to ask again", () => {
    const onRescan = vi.fn();
    render(
      <CategoryCard
        category="contacts"
        status="supported"
        note={null}
        inventory={{ category: "contacts", permission: "denied", count: 0, totalBytes: 0, albums: [], note: null, scannedMs: Date.now() }}
        scanning={false}
        selected={false}
        onToggle={() => undefined}
        onRescan={onRescan}
      />,
    );
    expect(screen.getByText("Нет доступа")).toBeTruthy();
    fireEvent.click(screen.getByLabelText("Подробнее"));
    fireEvent.click(screen.getByText("Запросить снова"));
    expect(onRescan).toHaveBeenCalled();
  });

  it("says no files were chosen instead of a wrong 'no access'", () => {
    render(
      <CategoryCard
        category="documents"
        status="action_required"
        note="compat.documents.pick"
        inventory={{ category: "documents", permission: "granted", count: 0, totalBytes: 0, albums: [], note: "not_picked", scannedMs: Date.now() }}
        scanning={false}
        selected={false}
        onToggle={() => undefined}
        onRescan={() => undefined}
      />,
    );
    expect(screen.getByText("Файлы не выбраны — отметьте снова, чтобы выбрать")).toBeTruthy();
    expect(screen.queryByText("Нет доступа")).toBeNull();
  });
});
