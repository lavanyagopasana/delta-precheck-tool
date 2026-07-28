import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";

// Promise-based custom confirm dialog -- a drop-in, prettier replacement for window.confirm().
// Usage:
//   const confirm = useConfirm();
//   if (!(await confirm({ title: "Delete project?", message: "...", confirmLabel: "Delete", danger: true }))) return;
const ConfirmContext = createContext(() => Promise.resolve(false));

export function useConfirm() {
  return useContext(ConfirmContext);
}

function WarningIcon({ danger }) {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {danger ? (
        <>
          <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
          <line x1="12" y1="9" x2="12" y2="13" />
          <line x1="12" y1="17" x2="12.01" y2="17" />
        </>
      ) : (
        <>
          <circle cx="12" cy="12" r="10" />
          <line x1="12" y1="16" x2="12" y2="12" />
          <line x1="12" y1="8" x2="12.01" y2="8" />
        </>
      )}
    </svg>
  );
}

function ConfirmDialog({ options, onCancel, onConfirm }) {
  const {
    title = "Are you sure?",
    message,
    confirmLabel = "Confirm",
    cancelLabel = "Cancel",
    danger = false,
  } = options;
  const confirmRef = useRef(null);

  useEffect(() => {
    confirmRef.current?.focus();
    const onKey = (e) => {
      if (e.key === "Escape") onCancel();
      if (e.key === "Enter") onConfirm();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onCancel, onConfirm]);

  const accent = danger ? "var(--color-red)" : "var(--color-primary)";
  const accentSoft = danger ? "var(--color-red-soft, #fdecec)" : "var(--color-primary-soft, #eef2ff)";

  return (
    <div className="confirm-overlay" onMouseDown={onCancel}>
      <div className="confirm-card" role="alertdialog" aria-modal="true" aria-label={title} onMouseDown={(e) => e.stopPropagation()}>
        <div className="confirm-body">
          <span className="confirm-icon" style={{ background: accentSoft, color: accent }}>
            <WarningIcon danger={danger} />
          </span>
          <div style={{ flex: 1 }}>
            <h3 className="confirm-title">{title}</h3>
            {message && <p className="confirm-message">{message}</p>}
          </div>
        </div>
        <div className="confirm-actions">
          <button type="button" className="btn secondary" onClick={onCancel}>
            {cancelLabel}
          </button>
          <button type="button" ref={confirmRef} className={`btn${danger ? " danger" : ""}`} onClick={onConfirm}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

export function ConfirmProvider({ children }) {
  const [state, setState] = useState(null);

  const confirm = useCallback(
    (options) => new Promise((resolve) => setState({ options: options || {}, resolve })),
    []
  );

  const close = useCallback((result) => {
    setState((prev) => {
      if (prev) prev.resolve(result);
      return null;
    });
  }, []);

  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      {state && (
        <ConfirmDialog
          options={state.options}
          onCancel={() => close(false)}
          onConfirm={() => close(true)}
        />
      )}
    </ConfirmContext.Provider>
  );
}
