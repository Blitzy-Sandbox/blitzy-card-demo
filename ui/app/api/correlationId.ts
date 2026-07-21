/**
 * Shared Axios instance + generated-client `Configuration` + correlation-id / bearer
 * request interceptor for the CardDemo UI.
 *
 * This is the hand-written heart of the SPA's backend-access layer. The React 19 UI
 * binds ONLY to the BFF, and every network call is made through the generated
 * `typescript-axios` client classes — all of which are wired to the single shared
 * Axios instance exported here (`axiosInstance`). Feature hooks construct API classes
 * as `new SomeApi(apiConfig, undefined, axiosInstance)`.
 *
 * Responsibilities (deliberately narrow — this is a skeleton seam, not a framework):
 *   1. Create the ONE shared Axios instance reused by every generated API class.
 *   2. Register, at module load (top-level side effect), a request interceptor that on
 *      EVERY outgoing request (a) stamps a fresh correlation-id header and (b) attaches
 *      the bearer token when the user is signed in.
 *   3. Build and export the generated-client `Configuration` with a same-origin base
 *      path so feature hooks can instantiate the typed API classes.
 *
 * This module is intentionally kept OUTSIDE `ui/app/api/generated/**` so that
 * regenerating the client (`npm run generate:api`) never overwrites it. It is a plain
 * `.ts` module: no React import, no JSX, no React state. The interceptor reads
 * `sessionStorage` per request (not React state), keeping it pure so it never needs
 * re-wiring across renders.
 *
 * The correlation-id hop is REAL even though authentication is a permissive stub: the
 * id propagates UI -> BFF -> card-svc and is logged via SLF4J MDC server-side.
 */
import axios, { type AxiosInstance, type InternalAxiosRequestConfig } from 'axios';
import { Configuration } from './generated';

/**
 * Per-request correlation-id header name. The exact casing MUST match the BFF OpenAPI
 * contract's reusable header parameter (contracts/bff.openapi.yaml) and the root
 * `CORRELATION_HEADER` environment variable; the id it carries propagates
 * UI -> BFF -> card-svc and is surfaced in trace logs via MDC server-side.
 */
export const CORRELATION_ID_HEADER = 'X-Correlation-ID';

/**
 * sessionStorage key that holds the bearer token. `ui/app/auth/AuthProvider.tsx` OWNS
 * writing and clearing this key; this interceptor only READS it. The two are
 * intentionally decoupled from React state, so this literal MUST stay in sync with the
 * one in `AuthProvider` (we deliberately do not import from `../auth` to avoid an
 * unnecessary cross-dependency).
 */
const TOKEN_STORAGE_KEY = 'carddemo.token';

/**
 * Generated-client `Configuration` shared by all API classes. An empty `basePath`
 * yields same-origin requests: the BFF spec paths already include the `/api` prefix, so
 * `'' + '/api/cards/{n}'` is forwarded to the `bff` service by the Vite dev proxy
 * (development) or nginx (production). Override `VITE_API_BASE_URL` only when the UI
 * must call a cross-origin BFF (not needed in the compose topology).
 */
export const apiConfig = new Configuration({
  basePath: import.meta.env.VITE_API_BASE_URL || '',
});

/**
 * The single shared Axios instance behind every generated API class. Feature hooks pass
 * it explicitly: `new SomeApi(apiConfig, undefined, axiosInstance)`.
 */
export const axiosInstance: AxiosInstance = axios.create();

// Registered at module load (top-level side effect): attach a fresh correlation id, and
// the bearer token when present, to every outgoing request on the shared instance.
axiosInstance.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  // (a) A fresh correlation id on every request. `crypto.randomUUID()` is browser-native
  //     (typed via the DOM lib) — no `uuid` package is used or declared.
  config.headers.set(CORRELATION_ID_HEADER, crypto.randomUUID());

  // (b) The bearer token when the user is signed in (permissive-auth stub token).
  const token = sessionStorage.getItem(TOKEN_STORAGE_KEY);
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`);
  }

  return config;
});
