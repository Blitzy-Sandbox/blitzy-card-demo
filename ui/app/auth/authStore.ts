import { useSyncExternalStore } from 'react';

/**
 * authStore — the single synchronized source of truth for the CardDemo bearer
 * token, backed directly by `sessionStorage`.
 *
 * WHY this module exists (finding P5-SEC-01):
 * The permissive-auth token lives in `sessionStorage` (key {@link TOKEN_STORAGE_KEY}).
 * Previously `AuthProvider` copied that value into a lazy `useState` initializer
 * ONCE at mount and never re-synced, so clearing the key while the app was mounted
 * (e.g. a tester removing `carddemo.token` via DevTools) left React holding a STALE
 * token: the guard kept rendering protected screens and the tracer request still
 * fired, only being rejected after a full reload. This store removes that drift by
 * making `sessionStorage` itself the source of truth and exposing it to React
 * through the first-class {@link useSyncExternalStore} subscription primitive.
 *
 * HOW it stays live:
 *   • {@link getToken} (the `useSyncExternalStore` snapshot) reads `sessionStorage`
 *     LIVE on every render, so any component that re-renders — notably `RouteGuard`,
 *     which re-renders on every client-side navigation via `useLocation` — observes
 *     the current token value, even for a same-document removal that fires no event.
 *   • {@link subscribe} additionally attaches `storage` / `focus` / `visibilitychange`
 *     listeners so OUT-OF-BAND changes (another tab clearing the key, or the tab
 *     regaining focus after an external change) also notify subscribers immediately.
 *
 * OWNERSHIP / DECOUPLING (unchanged contract): `ui/app/auth/**` OWNS writing and
 * clearing the token; `ui/app/api/correlationId.ts` only READS the same key and is
 * intentionally NOT coupled to this module (it re-reads `sessionStorage` per request
 * and shares only the literal key string — no cross-import). Mirrors the existing
 * module-store pattern in `ui/app/layout/drawerState.ts`.
 *
 * This is a plain `.ts` module (no JSX); the only React surface is the
 * {@link useAuthToken} hook.
 */

/**
 * sessionStorage key holding the bearer token. MUST stay in sync with the literal
 * in `ui/app/api/correlationId.ts` (deliberately duplicated to keep the request
 * interceptor decoupled from the auth module).
 */
const TOKEN_STORAGE_KEY = 'carddemo.token';

/** Subscribed React listeners, notified synchronously whenever the token changes. */
const listeners = new Set<() => void>();

/** Notify every subscriber that the token snapshot may have changed. */
function emit(): void {
  for (const listener of listeners) {
    listener();
  }
}

/**
 * Re-sync handler for OUT-OF-BAND `sessionStorage` mutations:
 *   • `storage`          — fired in OTHER documents/tabs when web storage changes.
 *   • window `focus`     — the tab regains focus (e.g. returning from DevTools).
 *   • `visibilitychange` — the tab becomes visible again.
 * (Same-document programmatic changes need no event: {@link getToken} reads
 * `sessionStorage` live on the next render.)
 */
function handleExternalChange(): void {
  emit();
}

/** Attach the global listeners once, when the first React subscriber arrives. */
function attachGlobalListeners(): void {
  window.addEventListener('storage', handleExternalChange);
  window.addEventListener('focus', handleExternalChange);
  document.addEventListener('visibilitychange', handleExternalChange);
}

/** Detach the global listeners when the last React subscriber leaves (leak-free). */
function detachGlobalListeners(): void {
  window.removeEventListener('storage', handleExternalChange);
  window.removeEventListener('focus', handleExternalChange);
  document.removeEventListener('visibilitychange', handleExternalChange);
}

/**
 * The bearer-token store. Exposes the live read + imperative mutators (owned by
 * `AuthProvider`) plus the `subscribe`/`getToken` pair required by
 * {@link useSyncExternalStore}.
 */
export const authStore = {
  /**
   * Current token read LIVE from `sessionStorage` (a primitive string | null →
   * compared by value, so it is a referentially-safe `useSyncExternalStore`
   * snapshot).
   */
  getToken(): string | null {
    return sessionStorage.getItem(TOKEN_STORAGE_KEY);
  },
  /** Persist the issued token and notify subscribers. */
  setToken(token: string): void {
    sessionStorage.setItem(TOKEN_STORAGE_KEY, token);
    emit();
  },
  /** Clear the token (sign-out) and notify subscribers. */
  clear(): void {
    sessionStorage.removeItem(TOKEN_STORAGE_KEY);
    emit();
  },
  /**
   * Register a listener; returns an unsubscribe function. Global DOM listeners are
   * ref-counted: attached on the first subscriber and detached on the last.
   * @param listener - Invoked whenever the token may have changed.
   */
  subscribe(listener: () => void): () => void {
    if (listeners.size === 0) {
      attachGlobalListeners();
    }
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
      if (listeners.size === 0) {
        detachGlobalListeners();
      }
    };
  },
} as const;

/**
 * React hook returning the LIVE bearer token (or `null`). Backed by
 * {@link useSyncExternalStore} so every consuming component re-reads
 * `sessionStorage` on each of its renders and re-renders on any token change.
 *
 * @returns the current bearer token, or `null` when signed out.
 */
export function useAuthToken(): string | null {
  return useSyncExternalStore(
    authStore.subscribe,
    authStore.getToken,
    // getServerSnapshot: this SPA renders client-only (Vite); returning the same
    // live reader keeps the hook safe if it is ever evaluated during SSR.
    authStore.getToken,
  );
}
