import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from './AuthProvider';

/**
 * Route guard for the authenticated area. Presence check ONLY — no business logic.
 * When there is no token, redirect to the permissive Sign-On screen; otherwise render
 * the matched child route via <Outlet/>. Used as a pathless layout-route element in
 * ui/app/routes.tsx: `<Route element={<RouteGuard />}> ...children... </Route>`.
 *
 * P5-SEC-01: `useLocation()` subscribes the guard to the router location so it
 * RE-EVALUATES on every client-side navigation. Combined with `useAuth()` reading
 * the LIVE token from the synchronized auth store, a token cleared while the app is
 * mounted (e.g. removed via DevTools) is detected on the very next navigation and
 * redirects to Sign-On BEFORE any protected screen mounts or fires a BFF request —
 * closing the stale-auth-state bypass. The guard renders <Navigate/> in that case,
 * so descendant screens (and their data hooks) never mount.
 */
export function RouteGuard() {
  // Re-render on every navigation so the live-token check below always runs against
  // the current sessionStorage value (not a value captured at initial mount).
  useLocation();
  const { token } = useAuth();

  if (!token) {
    return <Navigate to="/signon" replace />;
  }

  return <Outlet />;
}
