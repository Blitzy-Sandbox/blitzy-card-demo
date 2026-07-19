/**
 * DeferredNotice.tsx — the canonical `[DEFERRED]` status banner for placeholder
 * screens.
 *
 * The single shared, accessible notice that every non-tracer feature screen
 * renders to VISIBLY declare it is a deferred placeholder (finding M16). It
 * surfaces the EXACT canonical run-contract token `[DEFERRED]` (AAP §0.8:
 * "functional epics are tagged `[DEFERRED]`"; §0.7.2 / §0.8: "Visible
 * `[DEFERRED]`; zero functional implementation") — exported here as
 * {@link DEFERRED_TOKEN} so the literal is defined in exactly ONE place and can
 * never drift across the ~15 screens.
 *
 * Pure presentational, ZERO business logic: no data fetching, no BFF calls, no
 * state. Composed from `@mui/material` core only (`Alert` + `AlertTitle`) with
 * `severity="info"` (its color resolves to the theme `info` palette token — no
 * hardcoded colors, per AAP §0.5). It is announced politely to assistive
 * technology via `role="status"` (a non-urgent, persistent status — not an
 * `alert` — so it does not interrupt screen-reader users).
 *
 * Provenance: [SRC: COMEN01C | COMEN02Y.cpy:L19-L84] — the main-menu program and
 * copybook enumerate the functional options that are deferred this run; this
 * notice is the modern, generic marker for those deferred surfaces. REFERENCE
 * only.
 */
import { Alert, AlertTitle } from '@mui/material';
import type { SxProps, Theme } from '@mui/material';

/**
 * The exact canonical deferred token mandated by the run contract (AAP §0.8).
 * Defined once here so every screen renders an identical, drift-proof literal.
 *
 * NOTE: the token is exactly `[DEFERRED]` — never a prefixed or namespaced
 * variant, which the run contract explicitly forbids.
 */
export const DEFERRED_TOKEN = '[DEFERRED]';

/** Props for {@link DeferredNotice}. All optional. */
export interface DeferredNoticeProps {
  /**
   * Optional human-readable feature name (e.g. "Account View") appended after
   * the token for context. Purely presentational.
   */
  feature?: string;
  /**
   * Optional explanatory line rendered under the title. Defaults to a generic
   * "carries no business logic yet" message.
   */
  detail?: string;
  /** `sx` passthrough (theme tokens only) — applied to the Alert. */
  sx?: SxProps<Theme>;
}

/**
 * Renders the canonical, accessible `[DEFERRED]` notice.
 *
 * The title always contains the exact {@link DEFERRED_TOKEN} literal so the
 * marker is visible on-screen and detectable in the rendered DOM; when a
 * `feature` name is supplied it is appended for context.
 *
 * @param props - see {@link DeferredNoticeProps}
 * @returns an info `Alert` whose title contains the exact `[DEFERRED]` token.
 *
 * @example
 * // Account View screen
 * <DeferredNotice feature="Account View" />
 */
export function DeferredNotice({ feature, detail, sx }: DeferredNoticeProps) {
  return (
    <Alert severity="info" role="status" sx={sx}>
      <AlertTitle>
        {feature ? `${DEFERRED_TOKEN} — ${feature}` : DEFERRED_TOKEN}
      </AlertTitle>
      {detail ?? 'This screen is a deferred placeholder and carries no business logic yet.'}
    </Alert>
  );
}
