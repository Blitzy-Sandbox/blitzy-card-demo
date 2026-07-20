/**
 * routes.tsx — Central react-router-dom v7 route table for the CardDemo SPA.
 *
 * This module declares the entire client-side route surface of the React 19 +
 * TypeScript walking-skeleton UI. It is rendered by `ui/app/App.tsx`, which owns
 * the `<BrowserRouter>` provider, the MUI `ThemeProvider`, and the auth context.
 * Accordingly this file exports ONLY a `<Routes>` tree (via the `AppRoutes`
 * component) and MUST NOT create its own router provider.
 *
 * Route topology (three tiers):
 *   1. Public   — the permissive Sign-On screen at `/signon`, deliberately placed
 *                 OUTSIDE the auth guard so unauthenticated users can reach it
 *                 without triggering a redirect loop. [SRC: COSGN00.bms]
 *   2. Guarded  — two nested pathless layout routes wrap every authenticated
 *                 screen: `RouteGuard` (redirects to `/signon` when no token is
 *                 present, otherwise renders its `<Outlet/>`), which in turn wraps
 *                 `AppShell` (the persistent AppBar + Drawer chrome, which renders
 *                 the active screen through its own `<Outlet/>`). The guard is
 *                 declared once on the wrapping layout route; it therefore covers
 *                 every descendant screen without per-route duplication.
 *   3. Fallback — a catch-all `*` route that redirects unknown paths to `/menu`
 *                 (the guarded chain bounces to `/signon` if unauthenticated).
 *
 * Route provenance: every authenticated route traces to a legacy option of the
 * 10-entry CardDemo main menu [SRC: COMEN02Y | main menu]
 * (app/cpy/COMEN02Y.cpy L19–L80). The single LIVE end-to-end tracer slice is
 * main-menu option 4 "Credit Card View" (legacy program COCRDSLC), surfaced here
 * as `/cards/:cardNumber` → the fully-wired `CardDetail` screen.
 *
 * react-router-dom v7 note: route matching is rank-based, so more-specific static
 * segments automatically win over dynamic `:param` segments regardless of JSX
 * order (e.g. `/transactions/new` beats `/transactions/:transactionId`, and
 * `/cards` beats `/cards/:cardNumber`). No manual ordering workarounds are needed.
 */
import { Navigate, Route, Routes } from 'react-router-dom';

import { RouteGuard } from './auth/RouteGuard';
import { AppShell } from './layout/AppShell';

// Feature screens — default exports, one per screen folder under ui/features/**.
// The route paths below are the canonical contract; import paths/casing are
// aligned to the actual feature files authored by sibling agents.
import SignOn from '../features/signon/SignOn';
import MainMenu from '../features/menu/MainMenu';
import AccountView from '../features/account-view/AccountView';
import AccountUpdate from '../features/account-update/AccountUpdate';
import CardList from '../features/card-list/CardList';
import CardDetail from '../features/card-detail/CardDetail'; // LIVE TRACER screen
import CardUpdate from '../features/card-update/CardUpdate';
import TransactionList from '../features/transaction-list/TransactionList';
import TransactionAdd from '../features/transaction-add/TransactionAdd';
import TransactionView from '../features/transaction-view/TransactionView';
import Reports from '../features/reports/Reports';
import BillPayment from '../features/bill-payment/BillPayment';
import AdminMenu from '../features/admin-menu/AdminMenu';
import UserList from '../features/user-list/UserList';
import UserAdd from '../features/user-add/UserAdd';
import UserUpdate from '../features/user-update/UserUpdate';

/**
 * AppRoutes — the declarative route table for the CardDemo SPA.
 *
 * Rendered inside the `<BrowserRouter>` provided by `App.tsx`. Returns a
 * `<Routes>` element composed of the public Sign-On route, the guarded +
 * shell-wrapped authenticated screens, and a catch-all fallback.
 */
export function AppRoutes() {
  return (
    <Routes>
      {/* Public — permissive Sign-On (no token required). [SRC: COSGN00.bms] */}
      <Route path="/signon" element={<SignOn />} />

      {/* Authenticated area. RouteGuard redirects to /signon when no token is
          present; AppShell provides the AppBar + Drawer frame and a content
          <Outlet/>. Both are pathless layout routes that render <Outlet/>, so
          the guard is applied once and covers every descendant screen. */}
      <Route element={<RouteGuard />}>
        <Route element={<AppShell />}>
          {/* Landing — redirect the guarded index to the main menu. */}
          <Route index element={<Navigate to="/menu" replace />} />
          {/* Main menu — the 10-option navigation hub. [SRC: COMEN02Y | main menu] */}
          <Route path="/menu" element={<MainMenu />} />

          {/* Accounts — [SRC: COMEN02Y | main menu] opt 1 Account View (COACTVWC),
              opt 2 Account Update (COACTUPC). */}
          <Route path="/accounts/:accountId" element={<AccountView />} />
          <Route path="/accounts/:accountId/edit" element={<AccountUpdate />} />

          {/* Cards — [SRC: COMEN02Y | main menu] opt 3 Credit Card List (COCRDLIC),
              opt 4 Credit Card View (COCRDSLC) = LIVE TRACER, opt 5 Credit Card
              Update (COCRDUPC). Tracer read path: [SRC: COCRDSLC | CARDDAT]. */}
          <Route path="/cards" element={<CardList />} />
          <Route path="/cards/:cardNumber" element={<CardDetail />} />
          <Route path="/cards/:cardNumber/edit" element={<CardUpdate />} />

          {/* Transactions — [SRC: COMEN02Y | main menu] opt 6 Transaction List
              (COTRN00C), opt 7 Transaction View (COTRN01C), opt 8 Transaction Add
              (COTRN02C). v7 rank-based matching selects /transactions/new over
              /transactions/:transactionId automatically. */}
          <Route path="/transactions" element={<TransactionList />} />
          <Route path="/transactions/new" element={<TransactionAdd />} />
          <Route path="/transactions/:transactionId" element={<TransactionView />} />

          {/* Reports & Bill Payment — [SRC: COMEN02Y | main menu] opt 9 Transaction
              Reports (CORPT00C), opt 10 Bill Payment (COBIL00C). */}
          <Route path="/reports" element={<Reports />} />
          <Route path="/bill-payment" element={<BillPayment />} />

          {/* Admin & user management — admin menu (COADM01C) and the user CRUD
              screens (COUSR00C list / COUSR01C add / COUSR02C update). */}
          <Route path="/admin" element={<AdminMenu />} />
          <Route path="/users" element={<UserList />} />
          <Route path="/users/new" element={<UserAdd />} />
          <Route path="/users/:userId/edit" element={<UserUpdate />} />
        </Route>
      </Route>

      {/* Unknown paths → menu (the guarded chain bounces to /signon if the user
          is not authenticated). */}
      <Route path="*" element={<Navigate to="/menu" replace />} />
    </Routes>
  );
}
