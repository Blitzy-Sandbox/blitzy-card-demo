/**
 * StatusChip — Reusable card active-status indicator.
 *
 * A tiny, pure presentational component that renders a card's active status as
 * an MUI {@link https://mui.com/material-ui/react-chip/ | Chip}. It is the single
 * reusable renderer for the legacy `Y`/`N` "active" flag across the CardDemo UI:
 *
 *  - Card Detail tracer field `CRDSTCD` — "Card Active Y/N"
 *    [SRC: COCRDSL.bms:L115-119] (a single char `PIC X(01)`,
 *    [SRC: CVACT02Y.cpy:L10 CARD-ACTIVE-STATUS]).
 *  - The "Active" column of the Card List screen [SRC: COCRDLI.bms].
 *
 * Provenance: [SRC: COCRDSLC | CARDDAT]. The legacy BMS map and copybook are
 * REFERENCE only (never modified).
 *
 * Design-system compliance (AAP §0.5.2 "Active-status indicator → Chip,
 * color=\"success\"/\"default\""):
 *  - `@mui/material` CORE ONLY — no MUI X packages, no raw HTML badge.
 *  - Color is entirely token-driven via the `Chip` `color` prop
 *    (`"success"` → `palette.success.main`; `"default"` → neutral surface).
 *    No hex/rgb or inline style colors are used anywhere.
 *
 * This component performs NO data fetching — status flows in via props from the
 * feature hooks (e.g. the Card Detail `useCardDetail` hook). It stays generic and
 * reusable so any screen can render a consistent active-status indicator.
 */
import { Chip } from '@mui/material';
import type { ChipProps } from '@mui/material';

/**
 * Props for {@link StatusChip}.
 *
 * All props are optional so the chip can be dropped into any screen with sensible
 * defaults; only `status` typically needs to be supplied.
 */
export interface StatusChipProps {
  /**
   * Legacy `Y`/`N` active flag (`CRDSTCD` / `CARD-ACTIVE-STATUS`).
   *
   * Accepts `'Y'`/`'N'` (case-insensitive, whitespace-tolerant), a `boolean`, or
   * a nullish value. Anything that is not truthy-`'Y'`/`true` is treated as
   * inactive.
   */
  status?: string | boolean | null;
  /** Label shown when the status resolves to active. Defaults to `'Active'`. */
  activeLabel?: string;
  /** Label shown when the status resolves to inactive. Defaults to `'Inactive'`. */
  inactiveLabel?: string;
  /** `Chip` size passthrough. Defaults to `'small'`. */
  size?: ChipProps['size'];
  /** `Chip` variant passthrough. Defaults to `'filled'`. */
  variant?: ChipProps['variant'];
  /**
   * Optional `sx` passthrough for layout tweaks by consumers.
   *
   * Any values supplied here must resolve to MUI theme tokens (per AAP §0.5 the
   * skeleton forbids hardcoded colors/spacing); this component itself adds none.
   */
  sx?: ChipProps['sx'];
}

/**
 * Normalize the legacy active flag into a strict boolean.
 *
 * Legacy `CRDSTCD` holds `'Y'` (active) or `'N'`/blank (inactive); a wired
 * feature hook may instead pass a real `boolean`. Trimming and upper-casing makes
 * the string comparison whitespace- and case-tolerant.
 *
 * @param status - The raw status flag from props.
 * @returns `true` when the card is active, otherwise `false`.
 */
function isActive(status: StatusChipProps['status']): boolean {
  if (typeof status === 'boolean') {
    return status;
  }
  if (typeof status === 'string') {
    return status.trim().toUpperCase() === 'Y';
  }
  // null / undefined / anything else → inactive.
  return false;
}

/**
 * Render a card's active status as a token-colored MUI `Chip`.
 *
 * Active → `color="success"` (green success token); inactive →
 * `color="default"` (neutral token). The mapping matches AAP §0.5.2 exactly.
 *
 * @example
 * ```tsx
 * <StatusChip status="Y" />            // green "Active" chip
 * <StatusChip status="N" />            // grey "Inactive" chip
 * <StatusChip status={card.active} />  // boolean from a feature hook
 * ```
 */
export function StatusChip({
  status,
  activeLabel = 'Active',
  inactiveLabel = 'Inactive',
  size = 'small',
  variant = 'filled',
  sx,
}: StatusChipProps) {
  const active = isActive(status);
  return (
    <Chip
      label={active ? activeLabel : inactiveLabel}
      color={active ? 'success' : 'default'}
      size={size}
      variant={variant}
      sx={sx}
    />
  );
}
