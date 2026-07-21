/**
 * Loading.tsx — Reusable loading-state component for the CardDemo UI shell.
 *
 * Part of the greenfield `/ui/components` shared library. This is a PURE
 * PRESENTATIONAL component: it renders a loading indicator and performs NO data
 * fetching — the consuming feature hook (for example the Card Detail tracer's
 * `useCardDetail`) decides when to mount it.
 *
 * It is one of the three real states (loading / empty / error) that the Card
 * Detail tracer screen switches between per AAP §0.5.2 ("Loading state →
 * CircularProgress, Skeleton") and §0.6.3 (the tracer has "real" loading …
 * states). It is also reusable by the list / placeholder screens.
 *
 * Design-system compliance (AAP §0.5 — non-negotiable):
 *   • `@mui/material` core only, named imports; no `@mui/x-*`.
 *   • No hardcoded colour / spacing / radius — spacing flows through theme
 *     tokens (`sx` numeric multiples → `theme.spacing(n)`, `Stack` `spacing`),
 *     text colour through the `text.secondary` token.
 *   • Layout via MUI primitives (`Box`, `Stack`) — never a raw `<div>` flex/grid.
 *   • Accessibility: announced to assistive technology via `role="status"` /
 *     `aria-live` / `aria-busy` (AAP §0.5.5 accessibility precedence).
 *
 * Provenance: [SRC: COCRDSLC | CARDDAT] — the Card Detail tracer
 * (app/bms/COCRDSL.bms) requires a real loading state (AAP §0.6.3). This
 * component is generic/reusable; the BMS map is REFERENCE only.
 */
import type { SxProps, Theme } from '@mui/material';
import { Box, CircularProgress, Skeleton, Stack, Typography } from '@mui/material';

/**
 * Spacing-scale multiple used for the spinner variant's reserved vertical space.
 * Resolved through `theme.spacing()` (8px base → 200px) rather than a hardcoded
 * pixel value (finding m33 — no hardcoded dimensions).
 */
const SPINNER_MIN_HEIGHT_UNITS = 25;

/**
 * Spacing-scale multiple used for each skeleton row's height. Resolved through
 * `theme.spacing()` (8px base → 32px) rather than a hardcoded pixel value (m33).
 */
const SKELETON_ROW_HEIGHT_UNITS = 4;

/** Default skeleton row count when `rows` is omitted or non-finite. */
const DEFAULT_ROWS = 3;
/** Lower/upper bounds applied to `rows` so the skeleton can never render an
 * unbounded or non-positive number of placeholder lines (finding m33). */
const MIN_ROWS = 1;
const MAX_ROWS = 12;

/**
 * Screen-reader-only style: visually removes an element while keeping it in the
 * accessibility tree so assistive technology can announce it. This is the
 * standard "visually hidden" clipping technique (the same one MUI ships as
 * `@mui/utils` `visuallyHidden`); its values are an a11y mechanism, not design
 * tokens, so they are exempt from the no-hardcoded-values rule (finding m34).
 */
const visuallyHidden: SxProps<Theme> = {
  position: 'absolute',
  width: '1px',
  height: '1px',
  padding: 0,
  margin: '-1px',
  overflow: 'hidden',
  clip: 'rect(0 0 0 0)',
  whiteSpace: 'nowrap',
  border: 0,
};

/**
 * Props for {@link Loading}.
 */
export interface LoadingProps {
  /**
   * Visual style of the indicator.
   * - `'spinner'` → a centered {@link CircularProgress} with an optional caption.
   * - `'skeleton'` → a vertical {@link Stack} of {@link Skeleton} placeholder lines.
   *
   * @defaultValue `'spinner'`
   */
  variant?: 'spinner' | 'skeleton';
  /**
   * Optional caption rendered under the spinner as `Typography` `body2`. Only
   * consumed by the `'spinner'` variant.
   */
  label?: string;
  /**
   * Number of skeleton placeholder lines to render when `variant='skeleton'`.
   * Clamped to the inclusive range [{@link MIN_ROWS}, {@link MAX_ROWS}] and
   * floored; non-finite values fall back to {@link DEFAULT_ROWS} (finding m33).
   *
   * @defaultValue `3`
   */
  rows?: number;
  /**
   * Minimum block-size (vertical space) reserved for the spinner variant so the
   * indicator is visually centered within the available area. Accepts any MUI
   * `sx`-compatible dimension (a bare number is interpreted as pixels). When
   * omitted, defaults to a theme-`spacing()` multiple rather than a hardcoded
   * pixel value (finding m33).
   *
   * @defaultValue `theme.spacing(25)` (200px at the default 8px base)
   */
  minHeight?: number | string;
  /**
   * `sx` passthrough for one-off layout adjustments by the consumer. Merged
   * after the component's own styles; theme tokens only.
   */
  sx?: SxProps<Theme>;
}

/**
 * Renders a loading indicator in one of two variants. See {@link LoadingProps}.
 *
 * @example
 * ```tsx
 * // Centered spinner with a caption (Card Detail tracer loading state)
 * <Loading label="Loading card details…" />
 *
 * // Skeleton placeholder for a list/table region
 * <Loading variant="skeleton" rows={5} />
 * ```
 */
export function Loading({
  variant = 'spinner',
  label,
  rows = DEFAULT_ROWS,
  minHeight,
  sx,
}: LoadingProps) {
  // Accessible status text announced to assistive technology (finding m34). A
  // visible `label` doubles as the announcement; otherwise a hidden fallback is
  // rendered so `role="status"` always has an accessible name.
  const statusLabel = label ?? 'Loading…';

  if (variant === 'skeleton') {
    // Clamp the requested row count into a bounded, positive, integral range so
    // the skeleton can never render an unbounded or non-positive count (m33).
    const requestedRows = Number.isFinite(rows) ? Math.floor(rows) : DEFAULT_ROWS;
    const safeRows = Math.min(MAX_ROWS, Math.max(MIN_ROWS, requestedRows));

    return (
      <Stack
        spacing={1}
        // `role="status"` + `aria-live` announce the loading region, and the
        // visually-hidden label below gives it an accessible name (m34).
        // `aria-busy` is scoped to this region, not the whole document.
        role="status"
        aria-live="polite"
        aria-busy="true"
        // Merge the consumer `sx` last using MUI's array form so object, array,
        // and callback `sx` values are all honoured (a plain object spread would
        // silently break the array/callback cases).
        sx={[{ width: '100%' }, ...(Array.isArray(sx) ? sx : sx ? [sx] : [])]}
      >
        <Box component="span" sx={visuallyHidden}>
          {statusLabel}
        </Box>
        {Array.from({ length: safeRows }).map((_, index) => (
          <Skeleton
            key={index}
            variant="rectangular"
            // Height via a theme-`spacing()` multiple, not a hardcoded px (m33).
            sx={{ height: (theme) => theme.spacing(SKELETON_ROW_HEIGHT_UNITS) }}
          />
        ))}
      </Stack>
    );
  }

  return (
    <Box
      role="status"
      aria-live="polite"
      sx={[
        (theme) => ({
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 2,
          py: 4,
          // Default the reserved height to a theme-`spacing()` multiple rather
          // than a hardcoded px; an explicit `minHeight` prop still wins (m33).
          minHeight: minHeight ?? theme.spacing(SPINNER_MIN_HEIGHT_UNITS),
        }),
        ...(Array.isArray(sx) ? sx : sx ? [sx] : []),
      ]}
    >
      <CircularProgress />
      {label ? (
        // `text.secondary` is applied via `sx` rather than the `color` prop:
        // in MUI v9 the `color` prop no longer resolves dot-path palette tokens
        // (e.g. `color="text.secondary"` emits no colour), whereas `sx` does.
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          {label}
        </Typography>
      ) : (
        // No visible caption → provide a hidden accessible name for role=status.
        <Box component="span" sx={visuallyHidden}>
          {statusLabel}
        </Box>
      )}
    </Box>
  );
}
