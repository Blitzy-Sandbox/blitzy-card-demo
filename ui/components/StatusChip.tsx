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
   * Accepts `'Y'`/`'N'` (case-insensitive, whitespace-tolerant) or a `boolean`.
   * Only an explicit `'Y'`/`true` resolves to ACTIVE and an explicit
   * `'N'`/`false` resolves to INACTIVE; a nullish value or any unrecognized
   * string resolves to UNKNOWN — it is NOT silently coerced to inactive (finding
   * m39), so absent/dirty data reads as "Unknown" rather than a false "Inactive".
   */
  status?: string | boolean | null;
  /** Label shown when the status resolves to active. Defaults to `'Active'`. */
  activeLabel?: string;
  /** Label shown when the status resolves to inactive. Defaults to `'Inactive'`. */
  inactiveLabel?: string;
  /** Label shown when the status is unknown/absent. Defaults to `'Unknown'`. */
  unknownLabel?: string;
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

/** The three resolved states of the active flag. */
type ResolvedStatus = 'active' | 'inactive' | 'unknown';

/**
 * Normalize the legacy active flag into a tri-state value.
 *
 * Legacy `CRDSTCD` holds `'Y'` (active) or `'N'`/blank (inactive); a wired
 * feature hook may instead pass a real `boolean`. Trimming and upper-casing makes
 * the string comparison whitespace- and case-tolerant.
 *
 * Only an explicit `'Y'`/`true` → `'active'` and an explicit `'N'`/`false` →
 * `'inactive'`. Everything else (null, undefined, blank, or an unrecognized
 * string) → `'unknown'` so absent/invalid data is NOT misrepresented as a
 * definitive "Inactive" (finding m39).
 *
 * @param status - The raw status flag from props.
 * @returns `'active'`, `'inactive'`, or `'unknown'`.
 */
function resolveStatus(status: StatusChipProps['status']): ResolvedStatus {
  if (typeof status === 'boolean') {
    return status ? 'active' : 'inactive';
  }
  if (typeof status === 'string') {
    const normalized = status.trim().toUpperCase();
    if (normalized === 'Y') return 'active';
    if (normalized === 'N') return 'inactive';
    return 'unknown';
  }
  // null / undefined / anything else → unknown (never silently "inactive").
  return 'unknown';
}

/**
 * Render a card's active status as a token-colored MUI `Chip`.
 *
 * Active → `color="success"` (green success token); inactive and unknown →
 * `color="default"` (neutral token), differentiated by label. The active/default
 * color mapping matches AAP §0.5.2 exactly, while the distinct "Unknown" label
 * avoids reporting absent data as a definitive "Inactive" (finding m39).
 *
 * @example
 * ```tsx
 * <StatusChip status="Y" />            // green "Active" chip
 * <StatusChip status="N" />            // grey "Inactive" chip
 * <StatusChip status={card.active} />  // boolean from a feature hook
 * <StatusChip status={null} />         // grey "Unknown" chip (not "Inactive")
 * ```
 */
export function StatusChip({
  status,
  activeLabel = 'Active',
  inactiveLabel = 'Inactive',
  unknownLabel = 'Unknown',
  size = 'small',
  variant = 'filled',
  sx,
}: StatusChipProps) {
  const resolved = resolveStatus(status);
  // Only 'active' uses the success token; 'inactive' and 'unknown' both use the
  // neutral default token (AAP §0.5.2), and are distinguished by their label.
  const label =
    resolved === 'active'
      ? activeLabel
      : resolved === 'inactive'
        ? inactiveLabel
        : unknownLabel;
  return (
    <Chip
      label={label}
      color={resolved === 'active' ? 'success' : 'default'}
      size={size}
      variant={variant}
      sx={sx}
    />
  );
}
