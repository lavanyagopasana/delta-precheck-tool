import React from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import AttachmentPreview from "./AttachmentPreview";

// FILE_BASE is derived from runtime config at import time; the component only concatenates it onto
// filePath, so a stub keeps these tests independent of whatever origin the env resolves to.
jest.mock("../api/client", () => ({ FILE_BASE: "http://test-host" }));

function openModal() {
  return userEvent.click(screen.getByTitle("evidence.png"));
}

describe("AttachmentPreview", () => {
  it("renders an image thumbnail for an image attachment", () => {
    render(<AttachmentPreview filePath="/uploads/abc.png" fileName="evidence.png" />);
    const thumb = screen.getByAltText("evidence.png");
    expect(thumb).toHaveAttribute("src", "http://test-host/uploads/abc.png");
  });

  // Regression: an evidence image that the browser cannot decode -- a truncated/corrupt file, or a
  // non-image renamed to .png -- rendered the browser's broken-image icon with no way to reach the
  // file. Observed on the deployed app 2026-08-12 with a PNG that had a valid 8-byte signature but no
  // IHDR/IDAT/IEND chunks. Both <img> tags now have onError and degrade to the download card.
  // Report: .gstack/qa-reports/qa-report-deltaprechecks-cftools-live-2026-08-12.md
  it("falls back to a download card when an image fails to decode", async () => {
    render(<AttachmentPreview filePath="/uploads/abc.png" fileName="evidence.png" />);
    await openModal();

    // Two <img>s share the alt text: the row thumbnail and the large modal preview. The preview is
    // rendered last, so it's the one to fail here. fireEvent.error dispatches exactly what the browser
    // dispatches when decoding fails.
    const images = screen.getAllByAltText("evidence.png");
    fireEvent.error(images[images.length - 1]);

    expect(await screen.findByText(/can't be shown in the browser/i)).toBeInTheDocument();
    expect(document.querySelector("img[style*='78vw']")).toBeNull();
  });

  it("offers a download link for every attachment, including previewable ones", async () => {
    render(<AttachmentPreview filePath="/uploads/abc.png" fileName="evidence.png" />);
    await openModal();

    const link = screen.getByRole("link", { name: "Download" });
    expect(link).toHaveAttribute("href", "http://test-host/uploads/abc.png");
    expect(link).toHaveAttribute("download", "evidence.png");
  });

  // Before this, every non-image went to an <iframe>, so a .docx/.xlsx/.msg/.zip rendered as a blank
  // or garbage frame with no download affordance.
  it.each([
    ["report.docx", "docx"],
    ["pairs.xlsx", "xlsx"],
    ["thread.msg", "msg"],
    ["bundle.zip", "zip"],
  ])("shows a download card instead of an iframe for %s", async (fileName) => {
    render(<AttachmentPreview filePath={`/uploads/x-${fileName}`} fileName={fileName} />);
    await userEvent.click(screen.getByTitle(fileName));

    expect(screen.getByText(/can't be previewed here/i)).toBeInTheDocument();
    expect(document.querySelector("iframe")).toBeNull();
    expect(screen.getByRole("link", { name: "Download" })).toHaveAttribute("download", fileName);
  });

  it("uses the browser viewer for a PDF", async () => {
    render(<AttachmentPreview filePath="/uploads/x.pdf" fileName="proof.pdf" />);
    await userEvent.click(screen.getByTitle("proof.pdf"));

    expect(document.querySelector("iframe")).not.toBeNull();
    expect(screen.queryByText(/can't be previewed here/i)).toBeNull();
  });

  it("renders a player for a screen recording", async () => {
    render(<AttachmentPreview filePath="/uploads/x.mp4" fileName="sync-proof.mp4" />);
    await userEvent.click(screen.getByTitle("sync-proof.mp4"));

    expect(document.querySelector("video")).not.toBeNull();
  });

  // svg/html are served Content-Disposition: attachment by UploadDispositionFilter precisely so a
  // browser never renders them inline (same-origin /uploads + a scriptable format = stored XSS), so
  // the component must not point an <img> or <iframe> at one.
  it.each(["diagram.svg", "capture.html"])("never renders %s inline", async (fileName) => {
    render(<AttachmentPreview filePath={`/uploads/x-${fileName}`} fileName={fileName} />);
    await userEvent.click(screen.getByTitle(fileName));

    expect(document.querySelector("iframe")).toBeNull();
    expect(document.querySelector("img[style*='78vw']")).toBeNull();
    expect(screen.getByRole("link", { name: "Download" })).toBeInTheDocument();
  });

  it("renders nothing without a filePath", () => {
    const { container } = render(<AttachmentPreview filePath={null} fileName="x.png" />);
    expect(container).toBeEmptyDOMElement();
  });

  it("calls onRemove without opening the preview", async () => {
    const onRemove = jest.fn();
    render(
      <AttachmentPreview filePath="/uploads/abc.png" fileName="evidence.png" variant="full" onRemove={onRemove} />,
    );

    await userEvent.click(screen.getByLabelText("Remove attachment"));

    expect(onRemove).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("link", { name: "Download" })).toBeNull();
  });
});
