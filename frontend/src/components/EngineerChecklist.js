import React, { useEffect, useMemo, useRef, useState } from "react";

export default function EngineerChecklist({ options, selected, onChange }) {
  const [adding, setAdding] = useState(false);
  const [query, setQuery] = useState("");
  const containerRef = useRef(null);
  const inputRef = useRef(null);

  useEffect(() => {
    const handleClickOutside = (e) => {
      if (containerRef.current && !containerRef.current.contains(e.target)) {
        setAdding(false);
        setQuery("");
      }
    };
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, []);

  useEffect(() => {
    if (adding) inputRef.current?.focus();
  }, [adding]);

  const available = useMemo(
    () =>
      options.filter(
        // Compared case-insensitively. project_engineers stores whatever case was saved
        // ("Pravallika.Punumalli@...") while the roster serves the lowercased app_users value, so an
        // exact-match check offered an already-assigned person again as if they were unassigned.
        // Email is the identity key in this app -- comparing it case-sensitively is always a bug.
        (email) =>
          !selected.some((s) => s.toLowerCase() === email.toLowerCase()) &&
          email.toLowerCase().includes(query.trim().toLowerCase())
      ),
    [options, selected, query]
  );

  const add = (email) => {
    // Guard as well as filter: without it a case-variant could still be appended twice, e.g. by a
    // stale render, leaving the same person on the project under two spellings.
    if (selected.some((s) => s.toLowerCase() === email.toLowerCase())) {
      return;
    }
    onChange([...selected, email]);
    setQuery("");
    setAdding(false);
  };

  const remove = (email) =>
    onChange(selected.filter((e) => e.toLowerCase() !== email.toLowerCase()));

  const initials = (email) => (email || "?").trim().charAt(0).toUpperCase();

  if (!options.length) {
    return (
      <div className="checklist-empty">
        No engineers added yet — add one under Admin &gt; Manage Access.
      </div>
    );
  }

  return (
    <div className="engineer-picker" ref={containerRef}>
      {selected.length === 0 && !adding && (
        <span className="engineer-select-placeholder" style={{ marginRight: 8 }}>No engineers yet</span>
      )}

      {selected.map((email) => (
        <span key={email} className="engineer-chip">
          <span className="person-avatar">{initials(email)}</span>
          {email}
          <button type="button" onClick={() => remove(email)} aria-label={`Remove ${email}`}>
            &times;
          </button>
        </span>
      ))}

      {adding ? (
        <div className="engineer-picker-search">
          <input
            ref={inputRef}
            type="text"
            className="engineer-select-input"
            placeholder="Search engineers..."
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          <div className="engineer-select-dropdown">
            {available.length ? (
              available.map((email) => (
                <button type="button" key={email} className="engineer-select-option" onClick={() => add(email)}>
                  <span className="person-avatar small">{initials(email)}</span>
                  {email}
                </button>
              ))
            ) : (
              <div className="engineer-select-empty">
                {query ? "No matches" : "All engineers are already added"}
              </div>
            )}
          </div>
        </div>
      ) : (
        <button
          type="button"
          className="engineer-add-btn"
          onClick={() => setAdding(true)}
          aria-label="Add engineer"
          title="Add engineer"
        >
          +
        </button>
      )}
    </div>
  );
}
