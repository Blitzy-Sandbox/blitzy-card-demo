import { AppBar, Button, Toolbar, Typography } from '@mui/material';
import LogoutIcon from '@mui/icons-material/Logout';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';

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
 */
export function Header() {
  const navigate = useNavigate();
  const { logout } = useAuth();

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
        <Typography variant="h6" component="div" noWrap sx={{ flexGrow: 1 }}>
          CardDemo
        </Typography>
        <Button
          color="inherit"
          startIcon={<LogoutIcon />}
          onClick={handleSignOut}
          aria-label="Sign out"
        >
          Sign Out
        </Button>
      </Toolbar>
    </AppBar>
  );
}
