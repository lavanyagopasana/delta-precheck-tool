import React, { useEffect, useRef } from "react";

export default function Modal({ title, onClose, children, width = 640, closeIcon = false }) {
  const cardRef = useRef(null);
  const titleId = useRef(`modal-title-${Math.random().toString(36).slice(2, 9)}`).current;

  // Escape closes it. Backdrop click and the close button already did, which left the dialog
  // mouse-only -- a keyboard user could open it and then have no way out. Bound to the document
  // rather than the card so it works before anything inside has been focused.
  //
  // onClose is intentionally not in the dependency list: callers pass an inline arrow, so including
  // it would tear down and re-bind the listener on every render of the parent. The ref keeps the
  // handler pointing at the current callback without that churn.
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    const handleKeyDown = (e) => {
      if (e.key === "Escape") {
        e.stopPropagation();
        onCloseRef.current?.();
      }
    };
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, []);

  // Move focus into the dialog on open so the Escape handler and tabbing both start from inside it,
  // instead of leaving focus on the button that opened it behind the overlay.
  useEffect(() => {
    const previouslyFocused = document.activeElement;
    cardRef.current?.focus();
    // Hand focus back to whatever opened this when it closes, so keyboard position isn't lost.
    return () => {
      if (previouslyFocused instanceof HTMLElement) previouslyFocused.focus();
    };
  }, []);

  return (
    <div className="modal-overlay" onClick={onClose}>
      {/* role/aria-modal so assistive tech announces this as a dialog and treats the page behind it
          as inert -- without them it was just an unlabelled div. aria-labelledby points at the title
          that is already rendered, so the dialog announces itself by name. tabIndex={-1} makes the
          card focusable programmatically without adding it to the tab order. */}
      <div
        ref={cardRef}
        className="modal-card"
        style={{ width }}
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
      >
        <div className="modal-header">
          <strong id={titleId} style={{ fontSize: 15 }}>
            {title}
          </strong>
          {closeIcon ? (
            <button className="modal-close-icon" onClick={onClose} type="button" aria-label="Close">
              &times;
            </button>
          ) : (
            <button className="btn secondary" onClick={onClose} type="button">
              Close
            </button>
          )}
        </div>
        <div className="modal-body">{children}</div>
      </div>
    </div>
  );
}
