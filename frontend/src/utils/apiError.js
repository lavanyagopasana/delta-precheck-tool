// Single place that turns an axios error into a user-facing message. The backend's
// GlobalExceptionHandler always returns { message } on failures, so prefer that; otherwise fall
// back to a caller-supplied default. Extracted because `err.response?.data?.message || "..."` was
// repeated across nearly every page's catch block (3.2 duplicated logic / 3.3 consistent handling).
export function apiErrorMessage(err, fallback = "Something went wrong. Please try again.") {
  return err?.response?.data?.message || fallback;
}
