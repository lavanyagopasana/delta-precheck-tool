import React, { useState } from "react";
import { FILE_BASE } from "../api/client";
import { useToast } from "./Toast";

// How each extension should be PRESENTED. This is not an allowlist -- FileStorageService decides what
// may be uploaded. Anything not listed here still previews, just as a download card rather than a
// guess at inline rendering.
//
// Note svg/html are deliberately absent: UploadDispositionFilter serves those with
// `Content-Disposition: attachment`, so an <img>/<iframe> pointed at one would fail to load anyway.
// They fall through to the download card, which is the correct and safe presentation.
const KIND_BY_EXTENSION = {
  png: "image", jpg: "image", jpeg: "image", gif: "image", webp: "image", bmp: "image", avif: "image",
  // tif/tiff/heic/heif are uploadable but most browsers can't decode them in an <img>. Treating them
  // as images would reliably produce the broken-image icon this component now guards against, so
  // they're presented as downloads on purpose.
  pdf: "pdf",
  txt: "text", log: "text", json: "text", md: "text",
  // csv/tsv/har are text-ish but Chrome (and most browsers) downloads them rather than rendering them
  // inline, unlike txt/json/md -- an <iframe> pointed at one silently downloads the file and shows
  // nothing, with no way to detect that and fall back (unlike <img>/<video>'s onError). Rather than a
  // permanently blank modal, these skip the preview modal entirely and download immediately.
  csv: "download", tsv: "download", har: "download",
  mp4: "video", mov: "video", webm: "video", m4v: "video", mkv: "video",
  mp3: "audio", wav: "audio", m4a: "audio",
};

function getExtension(fileName) {
  if (!fileName) return "";
  const parts = fileName.split(".");
  return parts.length > 1 ? parts[parts.length - 1].toLowerCase() : "";
}

function iconFor(kind) {
  switch (kind) {
    case "image": return "🖼️";
    case "pdf": return "📕";
    case "text": return "📄";
    case "download": return "📄";
    case "video": return "🎬";
    case "audio": return "🎧";
    default: return "📎";
  }
}

export default function AttachmentPreview({ filePath, fileName, caption, variant = "chip", onRemove, showName = true }) {
  const [open, setOpen] = useState(false);
  const showToast = useToast();
  // A file can be a perfectly valid upload and still be undecodable by the browser -- a truncated or
  // corrupt PNG, or a non-image renamed to .png. Previously both <img> tags had no onError, so that
  // case rendered the browser's broken-image icon with no way to reach the file. Tracking the failure
  // lets it degrade to the same download card an unknown type gets.
  const [thumbFailed, setThumbFailed] = useState(false);
  const [previewFailed, setPreviewFailed] = useState(false);

  if (!filePath) return null;

  const url = `${FILE_BASE}${filePath}`;
  const displayName = fileName || "attachment";
  const kind = KIND_BY_EXTENSION[getExtension(fileName)] || "file";
  const isFull = variant === "full";
  const showThumb = kind === "image" && !thumbFailed;
  const thumbSize = isFull ? 30 : 26;

  const downloadLink = (
    <a
      href={url}
      download={displayName}
      onClick={(e) => e.stopPropagation()}
      className="btn secondary"
      style={{ padding: "4px 10px", fontSize: 12, textDecoration: "none" }}
    >
      Download
    </a>
  );

  return (
    <>
      <div
        onClick={(e) => {
          e.stopPropagation();
          if (kind === "download") {
            // No modal to open -- these download the moment the browser handles the URL, so a
            // preview modal on top of that would only ever show a blank iframe with no way to
            // know the download already happened.
            const a = document.createElement("a");
            a.href = url;
            a.download = displayName;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            showToast(`${displayName} downloaded.`, "success");
            return;
          }
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
        // Suppressed from Hotjar recordings: evidence is uploaded by engineers straight from customer
        // tenants, so both the file name and an image thumbnail routinely carry customer identifiers.
        // Applied to the whole chip rather than just the name so the thumbnail goes with it. Covers
        // every evidence render site, since PreCheckPanel and DeltaHistoryPanel both come through here.
        data-hj-suppress=""
      >
        {showThumb ? (
          <img
            src={url}
            alt={displayName}
            width={thumbSize}
            height={thumbSize}
            loading="lazy"
            onError={() => setThumbFailed(true)}
            style={{ width: thumbSize, height: thumbSize, objectFit: "cover", borderRadius: 4, flexShrink: 0 }}
          />
        ) : (
          <span style={{ fontSize: isFull ? 17 : 15 }}>{iconFor(kind)}</span>
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
            // Same reason as the chip, and more so: the open preview renders the evidence file itself
            // (a screenshot of a customer tenant, a CSV of their mailboxes), which is the single most
            // sensitive thing this app puts on screen.
            data-hj-suppress=""
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
              <strong style={{ fontSize: 13.5, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                {displayName}
              </strong>
              {/* Download is offered for every type, not just the ones that can't preview -- a reviewer
                  frequently wants the original file even when it renders fine (to attach to a ticket,
                  or to open a spreadsheet in Excel rather than squint at a frame). */}
              <div style={{ display: "flex", alignItems: "center", gap: 8, flexShrink: 0 }}>
                {downloadLink}
                <button className="btn secondary" style={{ padding: "4px 10px", fontSize: 12 }} onClick={() => setOpen(false)}>
                  Close ×
                </button>
              </div>
            </div>
            <div style={{ padding: 14, overflow: "auto", display: "flex", alignItems: "center", justifyContent: "center" }}>
              <PreviewBody
                kind={kind}
                url={url}
                displayName={displayName}
                failed={previewFailed}
                onFail={() => setPreviewFailed(true)}
              />
            </div>
          </div>
        </div>
      )}
    </>
  );
}

// Split out so the modal body stays readable: five presentations plus the fallback, rather than a
// nested ternary chain.
function PreviewBody({ kind, url, displayName, failed, onFail }) {
  // No Download button here on purpose: the modal header already carries one for every attachment,
  // and rendering a second identical action inside the card just duplicates it.
  if (failed || kind === "file") {
    return (
      <div style={{ textAlign: "center", padding: "28px 20px", maxWidth: 420 }}>
        <div style={{ fontSize: 40, marginBottom: 10 }}>{iconFor(failed ? "file" : kind)}</div>
        <div style={{ fontSize: 13.5, fontWeight: 600, marginBottom: 6, wordBreak: "break-all" }}>{displayName}</div>
        <div style={{ fontSize: 12.5, color: "var(--color-text-muted)" }}>
          {failed
            ? "This file can't be shown in the browser -- it may be a format your browser can't display, or the file may be damaged. Use Download above to open it with another app."
            : "This file type can't be previewed here. Use Download above to open it with another app."}
        </div>
      </div>
    );
  }

  if (kind === "image") {
    return (
      <img
        src={url}
        alt={displayName}
        loading="lazy"
        onError={onFail}
        style={{ maxWidth: "78vw", maxHeight: "72vh", objectFit: "contain" }}
      />
    );
  }

  if (kind === "video") {
    return (
      <video controls preload="metadata" onError={onFail} style={{ maxWidth: "78vw", maxHeight: "72vh" }}>
        <source src={url} />
      </video>
    );
  }

  if (kind === "audio") {
    return <audio controls preload="metadata" onError={onFail} src={url} style={{ width: "60vw", maxWidth: 520 }} />;
  }

  // pdf and text: the browser's own viewer is better than anything hand-rolled. An <iframe> can't
  // report a load failure for these the way <img>/<video> can, so the header's Download button is the
  // escape hatch rather than an onError fallback.
  return <iframe title={displayName} src={url} style={{ width: "70vw", height: "68vh", border: "none" }} />;
}
