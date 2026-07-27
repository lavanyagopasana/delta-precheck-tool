import React from "react";

export default function Modal({ title, onClose, children, width = 640, closeIcon = false }) {
  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-card" style={{ width }} onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <strong style={{ fontSize: 15 }}>{title}</strong>
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
