import { useSyncExternalStore } from 'react';

/**
 * drawerState — a tiny module-level store for the mobile navigation Drawer's
 * open/closed state, shared between the AppBar menu-toggle (rendered by
 * {@link ../layout/Header.Header}) and the temporary Drawer (rendered by
 * {@link ../layout/NavMenu.NavMenu}).
 *
 * WHY a module store rather than component state or React Context:
 * The toggle button and the Drawer live in two SIBLING components
 * (`Header` and `NavMenu`) that are composed side-by-side by `AppShell`
 * (`ui/app/layout/AppShell.tsx`). The AppBar's leading menu button must control
 * the Drawer that a sibling renders — so the state has to live ABOVE both.
 * A React Context would require wrapping both siblings in a Provider, i.e.
 * editing `AppShell`; this decoupled module store deliberately avoids that,
 * keeping the shell composition untouched. It is consumed through React's
 * first-class {@link useSyncExternalStore} subscription primitive (React 18+),
 * so components re-render correctly (including under StrictMode / concurrent
 * rendering) whenever the shared state changes.
 *
 * This addresses the QA finding "hamburger menu icon overlaps the AppBar title
 * on mobile/tablet": the toggle can now be rendered INSIDE the AppBar `Toolbar`
 * (standard MUI responsive-AppBar pattern) instead of as a free-floating,
 * fixed-position button layered over the title — while still driving the same
 * Drawer that `NavMenu` owns.
 *
 * The state is a single boolean; snapshots are primitives (compared by value),
 * so {@link getSnapshot} is referentially safe for `useSyncExternalStore`.
 */

/** Current open/closed state of the mobile navigation Drawer. */
let open = false;

/** Subscribed listeners notified synchronously on every state change. */
const listeners = new Set<() => void>();

/** Notify every subscriber that the snapshot changed. */
function emit(): void {
  for (const listener of listeners) {
    listener();
  }
}

/**
 * Set the open state and notify subscribers only when the value actually
 * changes, avoiding redundant re-renders.
 */
function set(next: boolean): void {
  if (open === next) {
    return;
  }
  open = next;
  emit();
}

/**
 * The mobile-navigation Drawer store. Exposes imperative mutators (bound to the
 * toggle button and Drawer close affordances) plus the `subscribe`/`getSnapshot`
 * pair required by {@link useSyncExternalStore}.
 */
export const drawerStore = {
  /** Flip the Drawer between open and closed (bound to the AppBar toggle). */
  toggle(): void {
    set(!open);
  },
  /** Open the Drawer. */
  open(): void {
    set(true);
  },
  /** Close the Drawer (bound to Drawer `onClose` and nav-item selection). */
  close(): void {
    set(false);
  },
  /**
   * Register a listener; returns an unsubscribe function.
   * @param listener - Invoked whenever the open state changes.
   */
  subscribe(listener: () => void): () => void {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  },
  /** Current open state (a primitive → referentially stable per value). */
  getSnapshot(): boolean {
    return open;
  },
} as const;

/**
 * React hook returning the live open/closed state of the mobile navigation
 * Drawer. Backed by {@link useSyncExternalStore} so any component using it
 * re-renders when the shared state changes.
 *
 * @returns `true` when the Drawer is open, otherwise `false`.
 */
export function useDrawerOpen(): boolean {
  return useSyncExternalStore(
    drawerStore.subscribe,
    drawerStore.getSnapshot,
    // getServerSnapshot: this SPA renders client-only (Vite), but providing the
    // same snapshot keeps the hook safe if it is ever evaluated during SSR.
    drawerStore.getSnapshot,
  );
}
