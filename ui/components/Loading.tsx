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
   *
   * @defaultValue `3`
   */
  rows?: number;
  /**
   * Minimum block-size (vertical space) reserved for the spinner variant so the
   * indicator is visually centered within the available area. Accepts any MUI
   * `sx`-compatible dimension (a bare number is interpreted as pixels).
   *
   * @defaultValue `200`
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
  rows = 3,
  minHeight = 200,
  sx,
}: LoadingProps) {
  if (variant === 'skeleton') {
    return (
      <Stack
        spacing={1}
        // Merge the consumer `sx` last using MUI's array form so object, array,
        // and callback `sx` values are all honoured (a plain object spread would
        // silently break the array/callback cases).
        sx={[{ width: '100%' }, ...(Array.isArray(sx) ? sx : sx ? [sx] : [])]}
        aria-busy="true"
        aria-live="polite"
      >
        {Array.from({ length: rows }).map((_, index) => (
          <Skeleton key={index} variant="rectangular" height={32} />
        ))}
      </Stack>
    );
  }

  return (
    <Box
      role="status"
      aria-live="polite"
      sx={[
        {
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 2,
          py: 4,
          minHeight,
        },
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
      ) : null}
    </Box>
  );
}
