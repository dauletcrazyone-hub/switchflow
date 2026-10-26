import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { toSelections, useApp } from "../../state/store";
import { CategoryCard, ContactPicker } from "./CategoryCard";

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

describe("ContactPicker", () => {
  // Found on a real Galaxy A25 -> iPhone 11 test: only "all 1901 contacts" could be moved, so three
  // test contacts could not be sent without the owner's whole address book.
  it("sends only the contacts found and picked", async () => {
    const getEntries = vi.fn().mockResolvedValue([
      { id: "7:0", name: "Мама", detail: "+77010000000" },
      { id: "7:1", name: "SwitchFlow Test Алия", detail: "+77030000001" },
      { id: "7:2", name: "SwitchFlow Test Бахыт", detail: "+77030000002" },
    ]);
    useApp.setState({
      backend: { getEntries } as never,
      selection: { categories: ["contacts"], albums: {}, contactsPolicy: "merge", includePrevious: false },
    });
    const view = render(<ContactPicker deviceId="a25" total={3} scannedMs={7} />);
    expect(screen.getByText(/Все контакты: 3/)).toBeTruthy();
    fireEvent.click(screen.getByText("Выбрать отдельные"));
    await waitFor(() => expect(screen.getByText("Мама")).toBeTruthy());
    expect(getEntries).toHaveBeenCalledWith("a25", "contacts");

    fireEvent.change(screen.getByLabelText("Поиск по имени, номеру или почте"), { target: { value: "switchflow test" } });
    expect(screen.queryByText("Мама")).toBeNull();
    fireEvent.click(screen.getByText("Только найденные (2)"));
    expect(screen.getByText(/Выбрано 2 из 3/)).toBeTruthy();
    expect(toSelections(useApp.getState().selection)).toEqual([{ category: "contacts", albumIds: null, entryIds: ["7:1", "7:2"] }]);

    // A new count: positions may now be other people, so the old picks are dropped, not sent.
    view.rerender(<ContactPicker deviceId="a25" total={3} scannedMs={8} />);
    expect(toSelections(useApp.getState().selection)).toEqual([{ category: "contacts", albumIds: null, entryIds: [] }]);
    expect(screen.getByText(/пересчитаны/)).toBeTruthy();

    fireEvent.click(screen.getByText("Все контакты"));
    expect(toSelections(useApp.getState().selection)).toEqual([{ category: "contacts", albumIds: null, entryIds: null }]);
  });
});
