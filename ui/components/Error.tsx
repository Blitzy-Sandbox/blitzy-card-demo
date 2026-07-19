/**
 * Error.tsx — Reusable error-state presentational component.
 *
 * Part of the shared `ui/components` library for the CardDemo walking skeleton.
 * This is one of the three real UI states (Loading / Empty / Error) that the
 * Card Detail tracer screen switches between (AAP §0.5.2, §0.6.3): the tracer
 * renders a *real* error state when the BFF / card-svc call fails. The component
 * is reusable by any screen.
 *
 * Pure presentational: it performs NO data fetching and holds no business logic —
 * it only displays an error value passed in via props, using the MUI `Alert`
 * component with `severity="error"` (which resolves to the `palette.error.main`
 * theme token — no hardcoded colors).
 *
 * Design system (AAP §0.5): MUI (`@mui/material`) core only, named imports,
 * theme-token driven. Renders semantic MUI components (never raw HTML control or
 * container elements), and every visual value traces to a theme token.
 *
 * Provenance: [SRC: COCRDSLC | CARDDAT]. The legacy Card Detail BMS map exposes a
 * red `ERRMSG` line (app/bms/COCRDSL.bms:L144-147, `COLOR=RED`); this `Alert` is
 * the modern equivalent. Legacy source is REFERENCE only.
 *
 * NAMING NOTE — the module-scoped export is named `Error`, which intentionally
 * shadows the global `Error` constructor *within this module* (for sibling-naming
 * consistency with `Loading` / `Empty` and as the stable symbol downstream
 * `ui/features` imports). Consequently this file derives the message
 * *structurally* via {@link resolveMessage} instead of a prototype-chain check
 * against the (shadowed) global error constructor, which keeps the export named
 * `Error` with zero shadow bug.
 */
import type { SxProps, Theme } from '@mui/material';
import { Alert, AlertTitle, Button, Snackbar } from '@mui/material';

/**
 * Props for the {@link Error} error-state component.
 *
 * All props are optional: with no props at all the component still renders a
 * valid error `Alert` showing {@link DEFAULT_MESSAGE}.
 */
export interface ErrorProps {
  /** Any thrown/rejected value (Error-like, string, or unknown). Used to derive a message. */
  error?: unknown;
  /** Explicit message; overrides the value derived from `error`. */
  message?: string;
  /** Optional bold title above the message (renders AlertTitle). */
  title?: string;
  /** If provided, renders a 'Retry' action button in the Alert. */
  onRetry?: () => void;
  /** If provided, renders the Alert close (X) and is called on dismiss. */
  onClose?: () => void;
  /** 'inline' → Alert in flow; 'snackbar' → Alert inside a Snackbar. Default 'inline'. */
  variant?: 'inline' | 'snackbar';
  /** Controls Snackbar visibility (variant='snackbar' only). Default true. */
  open?: boolean;
  /** sx passthrough (theme tokens only) — applies to the Alert. */
  sx?: SxProps<Theme>;
}

/** Fallback message shown when no usable message can be derived from the inputs. */
const DEFAULT_MESSAGE = 'Something went wrong. Please try again.';

/**
 * Derive a human-readable message from an arbitrary error value.
 *
 * Precedence: explicit `message` → string `error` → `error.message` (an object
 * carrying a non-empty string `message`, e.g. native JS `Error` instances and
 * Axios errors) → {@link DEFAULT_MESSAGE}.
 *
 * The object check is intentionally *structural* (duck-typed) rather than a
 * prototype-chain check against the global error constructor, because the
 * module-level `Error` export shadows that global constructor within this file.
 * Structural narrowing also handles cross-realm errors and plain error-shaped
 * objects that a prototype-chain check would miss.
 *
 * @param error - Arbitrary thrown/rejected value (unknown by design).
 * @param explicit - Optional explicit message that takes precedence over `error`.
 * @returns A non-empty message string suitable for display.
 */
function resolveMessage(error: unknown, explicit?: string): string {
  if (explicit && explicit.trim()) return explicit;
  if (typeof error === 'string' && error.trim()) return error;
  if (
    error !== null &&
    typeof error === 'object' &&
    'message' in error &&
    typeof (error as { message?: unknown }).message === 'string' &&
    (error as { message: string }).message.trim()
  ) {
    return (error as { message: string }).message;
  }
  return DEFAULT_MESSAGE;
}

/**
 * Reusable error-state component built on MUI `Alert` (`severity="error"`).
 *
 * Renders an inline `Alert` by default, or wraps the same `Alert` in a `Snackbar`
 * when `variant="snackbar"`. Optionally shows a bold `AlertTitle` (`title`), a
 * `Retry` action button (when `onRetry` is supplied), and a close (X) affordance
 * (when `onClose` is supplied). The error color is provided entirely by MUI's
 * `severity="error"` token (`palette.error.main`); the `Retry` button uses
 * `color="inherit"` so it inherits that token rather than hardcoding a color.
 *
 * @param props - See {@link ErrorProps}.
 *
 * @example
 * // Inline error with a retry action
 * <Error error={err} title="Load failed" onRetry={refetch} />
 *
 * @example
 * // Transient snackbar error with an explicit message
 * <Error variant="snackbar" open={open} message="Save failed" onClose={dismiss} />
 */
export function Error({
  error,
  message,
  title,
  onRetry,
  onClose,
  variant = 'inline',
  open = true,
  sx,
}: ErrorProps) {
  const text = resolveMessage(error, message);

  const alert = (
    <Alert
      severity="error"
      onClose={onClose}
      action={
        onRetry ? (
          <Button color="inherit" size="small" onClick={onRetry}>
            Retry
          </Button>
        ) : undefined
      }
      sx={sx}
    >
      {title ? <AlertTitle>{title}</AlertTitle> : null}
      {text}
    </Alert>
  );

  if (variant === 'snackbar') {
    return (
      <Snackbar
        open={open}
        onClose={onClose}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        {alert}
      </Snackbar>
    );
  }

  return alert;
}
