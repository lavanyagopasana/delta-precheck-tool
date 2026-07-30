import React, { useState } from "react";
import { FILE_BASE } from "../api/client";

const IMAGE_EXTENSIONS = ["png", "jpg", "jpeg", "gif", "webp", "bmp", "svg"];

function getExtension(fileName) {
  if (!fileName) return "";
  const parts = fileName.split(".");
  return parts.length > 1 ? parts[parts.length - 1].toLowerCase() : "";
}

export default function AttachmentPreview({ filePath, fileName, caption, variant = "chip", onRemove, showName = true }) {
  const [open, setOpen] = useState(false);

  if (!filePath) return null;

  const url = `${FILE_BASE}${filePath}`;
  const isImage = IMAGE_EXTENSIONS.includes(getExtension(fileName));
  const displayName = fileName || "attachment";
  const isFull = variant === "full";

  return (
    <>
      <div
        onClick={(e) => {
          e.stopPropagation();
          setOpen(true);
        }}
        style={
          isFull
            ? {
                display: "flex",
                alignItems: "center",
                gap: 10,
                padding: "12px 14px",
                border: "1px solid var(--color-primary-soft)",
                borderRadius: 10,
                cursor: "pointer",
                background: "var(--color-primary-soft)",
                width: "100%",
                boxSizing: "border-box",
              }
            : {
                display: "inline-flex",
                alignItems: "center",
                gap: 8,
                padding: "5px 10px",
                border: "1px solid var(--color-border)",
                borderRadius: 8,
                cursor: "pointer",
                background: "var(--color-surface)",
                maxWidth: 220,
              }
        }
        title={displayName}
      >
        {isImage ? (
          <img
            src={url}
            alt={displayName}
            width={isFull ? 30 : 26}
            height={isFull ? 30 : 26}
            loading="lazy"
            style={{ width: isFull ? 30 : 26, height: isFull ? 30 : 26, objectFit: "cover", borderRadius: 4, flexShrink: 0 }}
          />
        ) : (
          <span style={{ fontSize: isFull ? 17 : 15 }}>📄</span>
        )}
        {showName && (
          <div style={{ overflow: "hidden", flex: isFull ? 1 : undefined }}>
            <div
              style={{
                fontSize: isFull ? 13.5 : 12.5,
                fontWeight: 600,
                color: "var(--color-primary)",
                whiteSpace: "nowrap",
                textOverflow: "ellipsis",
                overflow: "hidden",
              }}
            >
              {displayName}
            </div>
            {caption && <div style={{ fontSize: 11, color: "var(--color-text-faint)" }}>{caption}</div>}
          </div>
        )}
        {isFull && onRemove && (
          <button
            type="button"
            aria-label="Remove attachment"
            onClick={(e) => {
              e.stopPropagation();
              onRemove();
            }}
            style={{
              border: "none",
              background: "transparent",
              color: "var(--color-text-muted)",
              fontSize: 18,
              lineHeight: 1,
              cursor: "pointer",
              padding: 4,
              flexShrink: 0,
            }}
          >
            ×
          </button>
        )}
      </div>

      {open && (
        <div
          onClick={() => setOpen(false)}
          style={{
            position: "fixed",
            inset: 0,
            background: "rgba(15, 18, 28, 0.6)",
            zIndex: 1000,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            padding: 24,
          }}
        >
          <div
            onClick={(e) => e.stopPropagation()}
            style={{
              background: "var(--color-surface)",
              borderRadius: 10,
              maxWidth: "85vw",
              maxHeight: "85vh",
              minWidth: 320,
              display: "flex",
              flexDirection: "column",
              overflow: "hidden",
              boxShadow: "0 20px 60px rgba(0, 0, 0, 0.35)",
            }}
          >
            <div
              style={{
                display: "flex",
                alignItems: "center",
                justifyContent: "space-between",
                gap: 12,
                padding: "10px 14px",
                borderBottom: "1px solid var(--color-border)",
              }}
            >
              <strong style={{ fontSize: 13.5 }}>{displayName}</strong>
              <button className="btn secondary" style={{ padding: "4px 10px", fontSize: 12 }} onClick={() => setOpen(false)}>
                Close ×
              </button>
            </div>
            <div style={{ padding: 14, overflow: "auto", display: "flex", alignItems: "center", justifyContent: "center" }}>
              {isImage ? (
                <img src={url} alt={displayName} loading="lazy" style={{ maxWidth: "78vw", maxHeight: "72vh", objectFit: "contain" }} />
              ) : (
                <iframe title={displayName} src={url} style={{ width: "70vw", height: "68vh", border: "none" }} />
              )}
            </div>
          </div>
        </div>
      )}
    </>
  );
}
