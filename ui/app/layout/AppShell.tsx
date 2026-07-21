import { Box, Toolbar } from '@mui/material';
import { Outlet } from 'react-router-dom';
import { Header } from './Header';
import { NavMenu } from './NavMenu';

/**
 * Application shell: the persistent chrome wrapping every authenticated screen.
 * Composes the fixed Header (AppBar), the permanent NavMenu (Drawer), and a main
 * content region that renders the active route via <Outlet/>.
 *
 * Used as a layout-route element in ui/app/routes.tsx
 * (RouteGuard -> AppShell -> feature screens), so it MUST render <Outlet/>.
 *
 * Standard MUI permanent-drawer layout: a flex Box root; the AppBar (Header) is
 * rendered above the Drawer (Header sets zIndex = drawer + 1) and spans the full
 * width; the main Box (flexGrow: 1) has a leading <Toolbar/> spacer that offsets
 * the fixed AppBar so page content starts below the header.
 */
export function AppShell() {
  return (
    <Box sx={{ display: 'flex' }}>
      <Header />
      <NavMenu />
      {/* P5-A11Y-03: `minWidth: 0` lets the main region shrink below its
          content's intrinsic min-content width (a flex child defaults to
          `min-width: auto`, which otherwise propagates wide children up and forces
          document-level horizontal overflow at extreme-narrow / 200%-zoom widths).
          Padding is reduced at the xs breakpoint so core content reflows without a
          horizontal scrollbar. */}
      <Box component="main" sx={{ flexGrow: 1, minWidth: 0, p: { xs: 2, sm: 3 } }}>
        <Toolbar />
        <Outlet />
      </Box>
    </Box>
  );
}
