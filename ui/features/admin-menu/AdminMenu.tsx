/**
 * AdminMenu.tsx — Admin Menu feature screen ([DEFERRED] placeholder).
 *
 * One of the ~16 non-tracer placeholder screens in the CardDemo React 19 + TS
 * walking skeleton (AAP §0.6.3: "the remaining ~16 screens use a shared
 * `PageContainer` plus a 'deferred' placeholder"). Its sole purpose is to make
 * the full navigation surface present and keep the route table type-checking
 * green — it carries ZERO business logic.
 *
 * [DEFERRED] — NO business logic (AAP §0.1.2, §0.7): this screen performs no
 * data fetching, imports/calls no generated API client, imports no auth
 * context, and makes NO BFF call of any kind. A typed placeholder is the
 * correct outcome; inventing functionality would be a failure. The only
 * behavior present is pure client-side navigation (react-router `Link`).
 *
 * Design-system compliance (AAP §0.5 — `@mui/material` core only): the screen
 * is composed exclusively from MUI core primitives (`List`, `ListItemButton`,
 * `ListItemText`, `ListSubheader`, `Stack`) plus the shared `PageContainer` and
 * `Empty` components (themselves MUI-core based). No `@mui/x-*` is used. All
 * spacing resolves to theme spacing tokens (`Stack spacing`); there are no
 * hardcoded colors, no hardcoded px values, and no raw DOM layout/heading/list/
 * anchor elements. Navigation uses `RouterLink` through MUI's polymorphic
 * `component` prop (client-side routing — never a full-page `<a href>` reload).
 *
 * Provenance: [SRC: COADM01C | COADM01.bms] — the legacy Admin Menu program
 * (`app/cbl/COADM01C.cbl`) and its BMS screen (`app/bms/COADM01.bms`, title
 * literal 'Admin Menu' at POS=(4,35)). The admin option table lives in
 * `app/cpy/COADM02Y.cpy`, which enumerates four security options; only the two
 * that map to static modern routes are surfaced as navigation here:
 *   1. User List (Security)  → COUSR00C → /users
 *   2. User Add  (Security)  → COUSR01C → /users/new
 * Options 3 (User Update → COUSR02C) and 4 (User Delete → COUSR03C) require a
 * selected user id and have no static route, so they are intentionally omitted.
 * The legacy artifacts are REFERENCE only; none of their logic is ported.
 *
 * Route contract: `ui/app/routes.tsx` binds `<Route path="/admin" element={
 * <AdminMenu />} />` via a DEFAULT import — hence the default export named
 * exactly `AdminMenu`, renderable with no required props.
 */
import { List, ListItemButton, ListItemText, ListSubheader, Stack } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import { PageContainer } from '../../components/PageContainer';
import { Empty } from '../../components/Empty';

/**
 * AdminMenu renders the deferred Admin Menu placeholder inside the standard
 * page frame, followed by a navigation-only list of the admin user-management
 * destinations that have static routes.
 *
 * @returns The Admin Menu screen element. Requires no props and holds no state.
 */
export default function AdminMenu() {
  return (
    <PageContainer title="Admin Menu">
      {/* Token-based vertical rhythm between the deferred notice and the nav
          list — `spacing={3}` maps to theme.spacing(3); no hardcoded margins. */}
      <Stack spacing={3}>
        {/* Required [DEFERRED] placeholder — declares the screen carries no
            implemented business logic yet. */}
        <Empty message="Admin Menu is not yet implemented (deferred)." />

        {/* Navigation only — pure client-side routing, no data fetching, no BFF
            call. [SRC: COADM01C | COADM01.bms] */}
        <List
          component="nav"
          aria-label="admin navigation"
          subheader={<ListSubheader component="div">User Administration</ListSubheader>}
        >
          <ListItemButton component={RouterLink} to="/users">
            <ListItemText primary="User List" secondary="Security" />
          </ListItemButton>
          <ListItemButton component={RouterLink} to="/users/new">
            <ListItemText primary="User Add" secondary="Security" />
          </ListItemButton>
        </List>
      </Stack>
    </PageContainer>
  );
}
