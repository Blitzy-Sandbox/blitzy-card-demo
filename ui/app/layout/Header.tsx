import { AppBar, Box, Button, IconButton, Toolbar, Typography } from '@mui/material';
import LogoutIcon from '@mui/icons-material/Logout';
import MenuIcon from '@mui/icons-material/Menu';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';
import { drawerStore, useDrawerOpen, MOBILE_NAV_ID } from './drawerState';

/**
 * Application header: a fixed AppBar carrying the CardDemo brand title and a
 * permissive sign-out action. Rendered by ui/app/layout/AppShell inside the
 * authenticated (guarded) area.
 *
 * Brand title provenance: legacy Sign-On banner [app/bms/COSGN00.bms] ("This is a
 * Credit Card Demo Application for Mainframe Modernization"); shortened to
 * "CardDemo" here.
 *
 * The AppBar renders ABOVE the permanent Drawer (zIndex = theme.zIndex.drawer + 1)
 * so it spans the full viewport width; NavMenu and AppShell each add a <Toolbar/>
 * spacer to offset this fixed bar.
 *
 * Responsive navigation toggle: on mobile/tablet (below the `md` breakpoint,
 * where NavMenu shows a temporary Drawer instead of the permanent rail) the
 * Toolbar leads with a menu (hamburger) IconButton — the standard MUI
 * responsive-AppBar pattern. Rendering it INSIDE the Toolbar flex flow (rather
 * than as a free-floating, fixed-position button) keeps it from overlapping the
 * "CardDemo" title: the title naturally flows to the right of the button, and
 * `color="inherit"` renders the icon in the AppBar's contrast colour (white on
 * the primary bar) instead of the low-contrast default grey (QA responsive
 * finding). The button drives the Drawer that NavMenu owns via the shared
 * {@link ./drawerState.drawerStore}, and is hidden at `md`+ where the permanent
 * Drawer is always visible.
 */
export function Header() {
  const navigate = useNavigate();
  const { logout } = useAuth();
  // Reflect the shared Drawer state so the toggle can expose an accurate
  // `aria-expanded` to assistive technology.
  const drawerOpen = useDrawerOpen();

  const handleSignOut = (): void => {
    // Auth is a permissive stub: logout() clears the real token + session state,
    // which makes the RouteGuard bounce to /signon; navigate explicitly too so the
    // redirect is immediate and deterministic.
    logout();
    navigate('/signon', { replace: true });
  };

  return (
    <AppBar position="fixed" sx={(theme) => ({ zIndex: theme.zIndex.drawer + 1 })}>
      <Toolbar>
        {/* Mobile/tablet navigation toggle (hidden at md+, where the permanent
            Drawer is shown). Sits at the Toolbar's start so the title flows after
            it — no overlap. Controls NavMenu's temporary Drawer via the store. */}
        <IconButton
          color="inherit"
          edge="start"
          aria-label="Open navigation menu"
          aria-expanded={drawerOpen}
          aria-controls={MOBILE_NAV_ID}
          onClick={() => drawerStore.toggle()}
          sx={{ mr: 2, display: { md: 'none' } }}
        >
          <MenuIcon />
        </IconButton>
        {/* minWidth:0 lets the title shrink inside the Toolbar flex row so its
            `noWrap` ellipsis engages instead of forcing the bar wider than the
            viewport at extreme-narrow / 200%-zoom widths (finding P5-A11Y-03). */}
        <Typography variant="h6" component="div" noWrap sx={{ flexGrow: 1, minWidth: 0 }}>
          CardDemo
        </Typography>
        <Button
          color="inherit"
          startIcon={<LogoutIcon />}
          onClick={handleSignOut}
          aria-label="Sign out"
          sx={{ flexShrink: 0 }}
        >
          {/* P5-A11Y-03: hide the text label at the xs breakpoint so the header
              collapses to hamburger + title + a compact icon-only Sign Out at very
              narrow widths (the 200% reflow proxy), avoiding horizontal overflow.
              The button keeps aria-label="Sign out", so it stays labeled for
              assistive technology at every breakpoint. */}
          <Box component="span" sx={{ display: { xs: 'none', sm: 'inline' } }}>
            Sign Out
          </Box>
        </Button>
      </Toolbar>
    </AppBar>
  );
}
