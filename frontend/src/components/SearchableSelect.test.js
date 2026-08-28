import React from "react";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import SearchableSelect from "./SearchableSelect";

// Real Metabase database names, including the deliberately confusable family: one customer gets
// several databases differing only by a product-type suffix, which is exactly why searching beats
// scrolling.
const DATABASES = [
  "artnet",
  "artnetemail",
  "bakkt",
  "bakkt2",
  "bakktemail",
  "bakktmsg",
  "morrisanimal",
  "washpost",
  "washpostemail",
];

function setup(props = {}) {
  const onChange = jest.fn();
  render(<SearchableSelect value="" onChange={onChange} options={DATABASES} {...props} />);
  return { onChange };
}

const openPanel = async () => userEvent.click(screen.getByRole("button"));

describe("SearchableSelect", () => {
  test("shows the placeholder until something is chosen, then the value", () => {
    const { rerender } = render(
      <SearchableSelect value="" onChange={() => {}} options={DATABASES} placeholder="Select a database..." />
    );
    expect(screen.getByText("Select a database...")).toBeInTheDocument();

    rerender(
      <SearchableSelect value="bakktmsg" onChange={() => {}} options={DATABASES} placeholder="Select a database..." />
    );
    expect(screen.getByText("bakktmsg")).toBeInTheDocument();
    expect(screen.queryByText("Select a database...")).not.toBeInTheDocument();
  });

  test("lists every option when opened, with a count", async () => {
    setup();
    await openPanel();

    expect(screen.getByText("9 available")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "morrisanimal" })).toBeInTheDocument();
  });

  test("filters case-insensitively and reports how many matched", async () => {
    setup();
    await openPanel();
    await userEvent.type(screen.getByPlaceholderText("Search..."), "BAKKT");

    // The count is what tells you whether to keep typing or start scrolling.
    expect(screen.getByText("4 of 9")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "bakktmsg" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "morrisanimal" })).not.toBeInTheDocument();
  });

  test("matches anywhere in the name, not just the start", async () => {
    // "email" is a suffix on several databases -- a prefix-only match would find none of them.
    setup();
    await openPanel();
    await userEvent.type(screen.getByPlaceholderText("Search..."), "email");

    expect(screen.getByText("3 of 9")).toBeInTheDocument();
  });

  test("selecting an option reports it and closes the panel", async () => {
    const { onChange } = setup();
    await openPanel();
    await userEvent.click(screen.getByRole("button", { name: "bakktemail" }));

    expect(onChange).toHaveBeenCalledWith("bakktemail");
    expect(screen.queryByPlaceholderText("Search...")).not.toBeInTheDocument();
  });

  test("Enter picks the only remaining match", async () => {
    const { onChange } = setup();
    await openPanel();
    await userEvent.type(screen.getByPlaceholderText("Search..."), "morris{Enter}");

    expect(onChange).toHaveBeenCalledWith("morrisanimal");
  });

  test("Enter does nothing when nothing has been typed", async () => {
    // Otherwise Enter on a freshly opened panel would silently choose whatever happened to be first.
    const { onChange } = setup();
    await openPanel();
    await userEvent.type(screen.getByPlaceholderText("Search..."), "{Enter}");

    expect(onChange).not.toHaveBeenCalled();
  });

  test("says so when nothing matches, rather than showing an empty list", async () => {
    setup();
    await openPanel();
    await userEvent.type(screen.getByPlaceholderText("Search..."), "nosuchdatabase");

    expect(screen.getByText(/No matches for/)).toBeInTheDocument();
  });

  test("Escape closes without selecting", async () => {
    const { onChange } = setup();
    await openPanel();
    await userEvent.type(screen.getByPlaceholderText("Search..."), "bakkt{Escape}");

    expect(screen.queryByPlaceholderText("Search...")).not.toBeInTheDocument();
    expect(onChange).not.toHaveBeenCalled();
  });

  test("clicking outside closes without selecting", async () => {
    const { onChange } = setup();
    await openPanel();
    expect(screen.getByPlaceholderText("Search...")).toBeInTheDocument();

    await userEvent.click(document.body);

    expect(screen.queryByPlaceholderText("Search...")).not.toBeInTheDocument();
    expect(onChange).not.toHaveBeenCalled();
  });

  test("cannot be opened while the option list is still loading", async () => {
    // The dropdown is the only way in, so opening an empty one would look like "Metabase has no
    // databases" rather than "they haven't arrived yet".
    render(
      <SearchableSelect value="" onChange={() => {}} options={[]} loadingLabel="Loading databases from Metabase..." />
    );

    expect(screen.getByRole("button")).toBeDisabled();
    await userEvent.click(screen.getByRole("button"));
    expect(screen.queryByPlaceholderText("Search...")).not.toBeInTheDocument();
  });

  test("cannot be opened while a save is in flight", async () => {
    setup({ disabled: true });
    await openPanel();

    expect(screen.queryByPlaceholderText("Search...")).not.toBeInTheDocument();
  });

  test("marks the currently selected option in the list", async () => {
    setup({ value: "bakkt2" });
    await openPanel();

    // Scoped to the option list: the trigger also renders the text "bakkt2", so an unscoped
    // getByRole would match two buttons.
    const options = screen.getAllByRole("button").filter((b) =>
      b.className.startsWith("searchable-select-option")
    );
    const byName = (name) => options.find((b) => b.textContent === name);

    expect(byName("bakkt2")).toHaveClass("is-selected");
    expect(byName("bakkt")).not.toHaveClass("is-selected");
  });
});
