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
 * SECURITY (finding M04) — this component NEVER renders server-provided free
 * text. A thrown/rejected `error` value (Axios error, RFC 7807 problem+json body,
 * or anything else) is mapped to an ALLOWLISTED, user-safe message keyed on the
 * HTTP status; server `detail` / `message` / `title` strings are deliberately
 * discarded so backend internals (stack traces, SQL, file paths) cannot leak into
 * the UI. For diagnostics we surface ONLY a correlation id, and only when it is a
 * canonical UUID (our CorrelationIdFilter guarantees that shape). Callers that
 * need a specific human message pass the trusted, developer-authored `message`
 * prop, which is the sole free-text input that is rendered as-is.
 *
 * NAMING NOTE — the module-scoped export is named `Error`, intentionally shadowing
 * the global `Error` constructor *within this module* (for sibling-naming
 * consistency with `Loading` / `Empty` and as the stable symbol downstream
 * `ui/features` imports). The safe-message mapping below never relies on the
 * global error constructor, so the shadow is harmless.
 */
import type { SxProps, Theme } from '@mui/material';
import { Alert, AlertTitle, Button, IconButton, Snackbar, Typography } from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';

/**
 * Props for the {@link Error} error-state component.
 *
 * All props are optional: with no props at all the component still renders a
 * valid error `Alert` showing {@link DEFAULT_MESSAGE}.
 */
export interface ErrorProps {
  /**
   * Any thrown/rejected value (Axios error, RFC 7807 body, or unknown). Treated
   * as UNTRUSTED: only its HTTP status and canonical-UUID correlation id are read;
   * its free text is never rendered (finding M04). Used to select a safe message.
   */
  error?: unknown;
  /**
   * Trusted, developer-authored message. When provided it overrides the mapped
   * `error` message and is rendered as-is (it is the sole free-text input shown).
   */
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

/** Fallback message when no HTTP status can be derived from the error value. */
const DEFAULT_MESSAGE = 'Something went wrong. Please try again.';

/**
 * Allowlist of user-safe messages keyed by HTTP status. These are the ONLY
 * strings ever shown for a server/thrown error — no server-provided text is
 * surfaced (finding M04). Unlisted 4xx/5xx statuses fall back to a generic
 * class message (client vs. server) via {@link toSafeError}.
 */
const SAFE_MESSAGES: Readonly<Record<number, string>> = {
  400: 'The request was invalid. Please check your input and try again.',
  401: 'Your session has expired or you are not signed in. Please sign in again.',
  403: 'You do not have permission to perform this action.',
  404: 'The requested item could not be found.',
  409: 'The request could not be completed due to a conflict.',
  422: 'The request could not be processed. Please check your input.',
  429: 'Too many requests. Please wait a moment and try again.',
  500: 'Something went wrong on our end. Please try again later.',
  502: 'The service is temporarily unavailable. Please try again later.',
  503: 'The service is temporarily unavailable. Please try again later.',
  504: 'The service took too long to respond. Please try again later.',
};

/** Canonical RFC 4122 UUID (8-4-4-4-12); correlation ids are validated to this. */
const CANONICAL_UUID =
  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/** A user-safe, redacted view of an error: an allowlisted message + optional id. */
interface SafeError {
  /** An allowlisted, display-safe message (never server-provided free text). */
  message: string;
  /** A validated canonical-UUID correlation id for diagnostics, when available. */
  correlationId?: string;
}

/** Read a property from an unknown value without widening to `any`. */
function getProp(value: unknown, key: string): unknown {
  return value !== null && typeof value === 'object' && key in value
    ? (value as Record<string, unknown>)[key]
    : undefined;
}

/**
 * Extract an HTTP status from an Axios-style error (`error.response.status`), a
 * fetch/RFC 7807-style error (`error.status`), or an RFC 7807 body
 * (`error.response.data.status`). Returns undefined when none is present.
 *
 * @param error - Arbitrary thrown/rejected value (unknown by design).
 * @returns The numeric HTTP status, or undefined.
 */
function extractStatus(error: unknown): number | undefined {
  const responseStatus = getProp(getProp(error, 'response'), 'status');
  if (typeof responseStatus === 'number') return responseStatus;
  const directStatus = getProp(error, 'status');
  if (typeof directStatus === 'number') return directStatus;
  const bodyStatus = getProp(getProp(getProp(error, 'response'), 'data'), 'status');
  if (typeof bodyStatus === 'number') return bodyStatus;
  return undefined;
}

/**
 * Extract a correlation id for diagnostics from an RFC 7807 body
 * (`error.response.data.correlationId`) or the `X-Correlation-ID` response
 * header (Axios lower-cases header keys). ONLY a canonical UUID is accepted; any
 * other value is dropped so no unvalidated server string is ever displayed (M04).
 *
 * @param error - Arbitrary thrown/rejected value (unknown by design).
 * @returns A validated canonical-UUID correlation id, or undefined.
 */
function extractCorrelationId(error: unknown): string | undefined {
  const response = getProp(error, 'response');
  const fromBody = getProp(getProp(response, 'data'), 'correlationId');
  if (typeof fromBody === 'string' && CANONICAL_UUID.test(fromBody)) return fromBody;
  const fromHeader = getProp(getProp(response, 'headers'), 'x-correlation-id');
  if (typeof fromHeader === 'string' && CANONICAL_UUID.test(fromHeader)) return fromHeader;
  return undefined;
}

/**
 * Map an arbitrary error to a SAFE, allowlisted message plus an optional
 * correlation id. A trusted, developer-authored `explicit` message (the
 * `message` prop) takes precedence and is shown as-is; the `error` value itself
 * is treated as untrusted and is NEVER rendered verbatim (finding M04). When a
 * status is present it selects an allowlisted message, falling back to a generic
 * server (5xx) or client (4xx/other) message; otherwise {@link DEFAULT_MESSAGE}.
 *
 * @param error - Arbitrary thrown/rejected value (unknown by design).
 * @param explicit - Optional trusted message that takes precedence over `error`.
 * @returns A {@link SafeError} safe for direct rendering.
 */
function toSafeError(error: unknown, explicit?: string): SafeError {
  if (explicit && explicit.trim()) return { message: explicit };
  const status = extractStatus(error);
  const correlationId = extractCorrelationId(error);
  let message = DEFAULT_MESSAGE;
  if (status !== undefined) {
    message = SAFE_MESSAGES[status] ?? (status >= 500 ? SAFE_MESSAGES[500] : SAFE_MESSAGES[400]);
  }
  return { message, correlationId };
}

/**
 * Reusable error-state component built on MUI `Alert` (`severity="error"`).
 *
 * Renders an inline `Alert` by default, or wraps the same `Alert` in a `Snackbar`
 * when `variant="snackbar"`. Optionally shows a bold `AlertTitle` (`title`), a
 * `Retry` action button (when `onRetry` is supplied), and a labeled close (X)
 * affordance (when `onClose` is supplied). Because MUI suppresses the built-in
 * `Alert` close whenever a custom `action` is present, `Retry` and close are
 * composed into a single `action` region so they COEXIST (finding m37) — supplying
 * `onRetry` no longer hides the close control. The error color is provided
 * entirely by MUI's `severity="error"` token (`palette.error.main`); the action
 * controls use `color="inherit"` so they inherit that token rather than hardcode.
 *
 * The rendered body text is always the allowlisted, redacted result of
 * {@link toSafeError}; a validated correlation id (when present) is shown as a
 * secondary "Reference ID" line for support diagnostics only (finding M04).
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
  const { message: text, correlationId } = toSafeError(error, message);

  // Compose the action region (finding m37): when a custom `action` is set MUI
  // suppresses the Alert's built-in close (X), so we render our OWN labeled close
  // button alongside Retry — the two now coexist instead of Retry hiding close.
  const action =
    onRetry || onClose ? (
      <>
        {onRetry ? (
          <Button color="inherit" size="small" onClick={onRetry}>
            Retry
          </Button>
        ) : null}
        {onClose ? (
          <IconButton aria-label="Close" color="inherit" size="small" onClick={onClose}>
            <CloseIcon fontSize="small" />
          </IconButton>
        ) : null}
      </>
    ) : undefined;

  const alert = (
    <Alert severity="error" action={action} sx={sx}>
      {title ? <AlertTitle>{title}</AlertTitle> : null}
      {text}
      {correlationId ? (
        <Typography
          variant="caption"
          component="p"
          sx={{ mt: 1, color: 'text.secondary' }}
        >
          Reference ID: {correlationId}
        </Typography>
      ) : null}
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
