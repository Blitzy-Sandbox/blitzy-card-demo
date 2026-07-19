/**
 * Empty.tsx — Reusable empty-state presentational component.
 *
 * Part of the greenfield `ui/components` shared library for the CardDemo
 * walking skeleton. Renders a centered, muted message when a view has no data
 * to display. It is one of the three "real states" (loading / empty / error)
 * that the Card Detail tracer screen switches between per AAP §0.5.2
 * ("Empty state → Typography variant='body2'") and §0.6.3, and it is equally
 * reusable by list / placeholder screens (e.g. a "no rows" state).
 *
 * Pure presentational: NO data fetching, NO BFF calls, NO side effects.
 *
 * Design-system compliance (AAP §0.5 — `@mui/material` core only):
 *  - Layout via MUI `Box` (never a raw flex `<div>`).
 *  - Text via MUI `Typography variant="body2"` (never a raw `<p>`/heading).
 *  - Muted color via the `text.secondary` palette token, applied through `sx`
 *    (never a hardcoded hex). MUI v9's `Typography` `color` prop only honors
 *    named palette tokens (e.g. "primary", "textSecondary") and silently drops
 *    dotted palette paths like "text.secondary"; dotted paths must go through
 *    `sx`, which resolves them to `theme.palette.text.secondary` (see the note
 *    at the render site).
 *  - Vertical / horizontal padding via theme spacing tokens (`py` / `px`).
 *  - MUI v9 removed the deprecated `Typography` bottom-margin text prop; use
 *    `sx` for spacing instead (no bottom margin is needed here).
 *
 * Provenance: [SRC: COCRDSLC | CARDDAT] — the Card Detail tracer requires a
 * real empty state (AAP §0.6.3). The legacy BMS map is REFERENCE only.
 */
import type { ReactNode } from 'react';
import type { SxProps, Theme } from '@mui/material';
import { Box, Typography } from '@mui/material';

/**
 * Props for the {@link Empty} component.
 */
export interface EmptyProps {
  /** Message to display. Default `'No data to display.'`. Ignored if `children` provided. */
  message?: string;
  /** Optional custom content that overrides `message` when provided. */
  children?: ReactNode;
  /** `sx` passthrough for layout overrides (theme tokens only). */
  sx?: SxProps<Theme>;
}

/**
 * Empty renders a centered, muted empty-state message.
 *
 * When `children` are supplied they take precedence over `message`, allowing
 * callers to compose richer empty states (e.g. an icon plus text) while keeping
 * the centered, accessible container. The container carries `role="status"` so
 * assistive technologies announce the state politely.
 *
 * @param props - see {@link EmptyProps}
 * @returns the empty-state element
 */
export function Empty({ message = 'No data to display.', children, sx }: EmptyProps) {
  return (
    <Box
      role="status"
      sx={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        textAlign: 'center',
        py: 4,
        px: 2,
        ...sx,
      }}
    >
      {children ?? (
        // Muted body2 text for the empty state. The `text.secondary` palette
        // token is applied via `sx` rather than the `color` prop because MUI v9
        // only resolves dotted palette paths (e.g. "text.secondary") through the
        // sx system — passing them to the `color` prop is silently ignored,
        // leaving the text at full-emphasis `text.primary`. Same token, correct
        // muted render (theme.palette.text.secondary), per AAP §0.5.2.
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          {message}
        </Typography>
      )}
    </Box>
  );
}
