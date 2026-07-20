/**
 * App.tsx — Root application composition for the CardDemo React 19 SPA.
 *
 * This is the single COMPOSITION ROOT of the walking-skeleton UI (AAP §0.6.1
 * Group 3, §0.6.3). It wires the four cross-cutting providers together in one
 * fixed order and then renders the declarative route table (`AppRoutes`). It is
 * mounted by `ui/app/main.tsx` (`createRoot(...).render(<App/>)`).
 *
 * Provider tree (outermost → innermost) and WHY the order is fixed:
 *
 *   <ThemeProvider theme={theme}>   MUI v9 theme tokens (palette, spacing,
 *   │                               typography, shape, breakpoints, elevation).
 *   │                               MUST be outermost so BOTH the global reset
 *   │                               below and every descendant can read the
 *   │                               tokens via `sx` / `styled` / `useTheme`.
 *   │
 *   ├─ <CssBaseline />              The ONE global CSS reset for the whole app.
 *   │                               Consumes theme tokens to normalise element
 *   │                               styling, so it must sit INSIDE ThemeProvider.
 *   │                               Rendered exactly once here — never duplicated
 *   │                               in a child (Do NOT add a second reset).
 *   │
 *   └─ <AuthProvider>              Permissive auth context: owns the bearer-token
 *      │                            state + sessionStorage. Placed OUTSIDE the
 *      │                            router because it uses NO router hooks (verified
 *      │                            against the auth folder contract — token state
 *      │                            only). The auth-dependent, navigation-driven
 *      │                            pieces (RouteGuard + the Sign-On screen) live
 *      │                            INSIDE BrowserRouter via AppRoutes, so they
 *      │                            still resolve routing hooks correctly.
 *      │                            (If AuthProvider ever needs `useNavigate`, move
 *      │                            BrowserRouter to wrap AuthProvider instead.)
 *      │
 *      └─ <BrowserRouter>          History-API router. Owned HERE — deliberately
 *         │                         NOT inside routes.tsx — so `AppRoutes` can be a
 *         │                         pure `<Routes>` tree and every routing hook
 *         │                         (useNavigate/useParams/useLocation) used by the
 *         │                         guard, shell and screens has a router context.
 *         │
 *         └─ <AppRoutes />         The full client-side route surface (public
 *                                  Sign-On, guarded + shell-wrapped authenticated
 *                                  screens, catch-all fallback).
 *
 * Design constraints honoured (agent brief):
 *   • React 19 automatic JSX runtime (`jsx: "react-jsx"`) — no `import React`.
 *   • No path aliases configured — local imports are relative siblings.
 *   • MUI v9 core — `CssBaseline`/`ThemeProvider` imported from the package root
 *     `@mui/material` (both are re-exported there).
 *   • The Roboto webfont is loaded via a <link> in `ui/index.html`; this module
 *     imports no font packages.
 *
 * This component is intentionally a PURE composition root: no business logic, no
 * data fetching, and no layout markup. The application chrome (AppBar + Drawer)
 * lives in `ui/app/layout/AppShell`, reached through the guarded routes.
 */
import { CssBaseline, ThemeProvider } from '@mui/material';
import { BrowserRouter } from 'react-router-dom';

import { theme } from './theme/theme';
import { AuthProvider } from './auth/AuthProvider';
import { AppRoutes } from './routes';

/**
 * App — the root React component.
 *
 * Composes theme → global reset → auth context → router → routes. Takes no props
 * and renders no UI of its own beyond the provider chain; all visible surfaces are
 * produced by the routed screens inside `AppRoutes`.
 *
 * @returns The fully-composed application element tree.
 */
export default function App() {
  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <AuthProvider>
        <BrowserRouter>
          <AppRoutes />
        </BrowserRouter>
      </AuthProvider>
    </ThemeProvider>
  );
}
