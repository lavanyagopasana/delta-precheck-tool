import React, { useEffect, useMemo, useRef, useState } from "react";

/**
 * A single-select dropdown with a search box.
 *
 * Exists because a native `<select>` becomes unusable past a few dozen options and Metabase serves
 * 159 database names: finding "bakktmsg" meant scrolling a list where every entry looks like every
 * other entry. A native select also can't be typed into beyond first-letter jumping.
 *
 * Deliberately built on the same interaction idiom as {@link EngineerChecklist} -- a search input with
 * an absolutely-positioned dropdown of option buttons, closed by clicking outside -- so the app has one
 * way of picking from a long list rather than two. The CSS classes mirror that component's
 * (`.engineer-select-*`) under neutral names, since a database picker shouldn't be styled by rules
 * named after engineers.
 *
 * Filtering is a plain case-insensitive substring match on the option text. No fuzzy matching: these
 * are database names people already know, and a fuzzy match that reorders results makes the list
 * harder to scan, not easier.
 */
export default function SearchableSelect({
  value,
  onChange,
  options,
  placeholder = "Select...",
  loadingLabel,
  disabled = false,
  ariaLabel,
}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const containerRef = useRef(null);
  const inputRef = useRef(null);

  useEffect(() => {
    const handleClickOutside = (e) => {
      if (containerRef.current && !containerRef.current.contains(e.target)) {
        setOpen(false);
        setQuery("");
      }
    };
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, []);

  useEffect(() => {
    if (open) inputRef.current?.focus();
  }, [open]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return options;
    return options.filter((o) => o.toLowerCase().includes(q));
  }, [options, query]);

  const pick = (option) => {
    onChange(option);
    setQuery("");
    setOpen(false);
  };

  const handleKeyDown = (e) => {
    if (e.key === "Escape") {
      setOpen(false);
      setQuery("");
    } else if (e.key === "Enter" && filtered.length) {
      // Type enough to narrow it to one and press Enter -- the whole point of searching. Only acts
      // when something is actually typed, so Enter on an unfiltered list can't silently pick option 1.
      e.preventDefault();
      if (query.trim()) pick(filtered[0]);
    }
  };

  if (loadingLabel) {
    return (
      <button type="button" className="searchable-select-trigger" disabled>
        <span className="searchable-select-placeholder">{loadingLabel}</span>
      </button>
    );
  }

  return (
    <div className="searchable-select" ref={containerRef}>
      <button
        type="button"
        className="searchable-select-trigger"
        onClick={() => setOpen((o) => !o)}
        disabled={disabled}
        aria-label={ariaLabel}
        aria-expanded={open}
      >
        {value ? (
          <span className="searchable-select-value">{value}</span>
        ) : (
          <span className="searchable-select-placeholder">{placeholder}</span>
        )}
        <span className="searchable-select-caret" aria-hidden="true">
          ▾
        </span>
      </button>

      {open && (
        <div className="searchable-select-panel">
          <input
            ref={inputRef}
            type="text"
            className="searchable-select-input"
            placeholder="Search..."
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={handleKeyDown}
          />
          {/* The count tells you whether to keep typing or start scrolling. With 159 options,
              "3 of 159" and "159 of 159" call for very different next actions. */}
          <div className="searchable-select-count">
            {filtered.length === options.length
              ? `${options.length} available`
              : `${filtered.length} of ${options.length}`}
          </div>
          <div className="searchable-select-list">
            {filtered.length ? (
              filtered.map((option) => (
                <button
                  type="button"
                  key={option}
                  className={
                    option === value
                      ? "searchable-select-option is-selected"
                      : "searchable-select-option"
                  }
                  onClick={() => pick(option)}
                >
                  {option}
                </button>
              ))
            ) : (
              <div className="searchable-select-empty">No matches for “{query.trim()}”</div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
