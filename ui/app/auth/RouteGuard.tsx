import { Navigate, Outlet } from 'react-router-dom';
import { useAuth } from './AuthProvider';

/**
 * Route guard for the authenticated area. Presence check ONLY — no business logic.
 * When there is no token, redirect to the permissive Sign-On screen; otherwise render
 * the matched child route via <Outlet/>. Used as a pathless layout-route element in
 * ui/app/routes.tsx: `<Route element={<RouteGuard />}> ...children... </Route>`.
 */
export function RouteGuard() {
  const { token } = useAuth();

  if (!token) {
    return <Navigate to="/signon" replace />;
  }

  return <Outlet />;
}
