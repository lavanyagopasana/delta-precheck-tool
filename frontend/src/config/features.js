// Feature switches that are a product decision rather than a per-environment setting.
//
// Not in runtimeConfig.js: that file resolves values supplied by the environment (Azure ids, the API
// base, the Hotjar id). These are decisions about what the product currently offers, so they belong
// in the source where a reviewer sees them change.

/**
 * The Metabase integration's UI: the per-project database pickers and the "Get process status"
 * panel that reads live migration figures out of Metabase.
 *
 * <p>ON. It was switched off briefly on 2026-09-03 and switched straight back: removal is still
 * being confirmed, and the integration has to keep working until that decision is made.
 *
 * <p>The flag exists precisely so that decision costs one line either way. No Metabase code was ever
 * deleted — MetabaseClient, MetabaseStatusService, the status panel and the project metabase
 * endpoints are all untouched — which is why turning it back on required nothing but this value.
 * With METABASE_API_KEY unset nothing reaches out to Metabase regardless.
 */
export const METABASE_UI_ENABLED = true;
