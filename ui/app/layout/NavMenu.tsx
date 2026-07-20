import {
  Box,
  Divider,
  Drawer,
  List,
  ListItem,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Toolbar,
} from '@mui/material';
import { Link as RouterLink, useLocation } from 'react-router-dom';
import { ADMIN_NAV_ITEM, MAIN_MENU_ITEMS, type NavItem } from './navItems';
import { drawerStore, useDrawerOpen } from './drawerState';

/**
 * NavMenu — the primary CardDemo navigation.
 *
 * Findings addressed:
 *   • M23 — the menu is now sourced ONCE from ui/app/layout/navItems.ts
 *     ({@link MAIN_MENU_ITEMS} = the canonical 10 options; {@link ADMIN_NAV_ITEM}
 *     kept in its own section as a separate administrative flow). No route data
 *     is duplicated here or in MainMenu.
 *   • M21 — responsive: a `temporary` Drawer at the xs/sm breakpoints and a
 *     `permanent` Drawer at md+ (so mobile is no longer obstructed by an
 *     always-open 240px rail).
 *   • M22 — accessibility: the list lives inside a labeled `<nav>` landmark, each
 *     item is a real router link (`ListItemButton` backed by react-router
 *     `Link`), and the current route is marked with `aria-current="page"`.
 *   • Hamburger/AppBar-title overlap (QA responsive finding): the mobile
 *     open/close toggle is NO LONGER a free-floating, fixed-position button
 *     layered over the AppBar title. NavMenu now OWNS the Drawer only; the toggle
 *     lives inside the AppBar `Toolbar` ({@link ./Header.Header}), the standard
 *     MUI responsive-AppBar pattern. The two are decoupled through the shared
 *     {@link ./drawerState.drawerStore} module store, so the AppBar button and
 *     this Drawer stay in sync without lifting state into `AppShell`.
 *
 * The temporary Drawer's open state is read from the shared store via
 * {@link useDrawerOpen}; closing (backdrop click, Escape, or selecting an item)
 * calls {@link drawerStore.close}. MUI's temporary Drawer (a Modal) still
 * handles Escape-to-close and focus restoration to the invoking toggle.
 */

/**
 * Width of the navigation Drawer, expressed in MUI spacing units (8px base):
 * 30 * 8px = 240px. Kept token-derived (theme.spacing) per the design-system
 * rule that every value resolves to a theme token.
 */
const DRAWER_WIDTH_UNITS = 30;

export function NavMenu() {
  const { pathname } = useLocation();
  // Mobile Drawer open state is shared with the AppBar toggle (Header) via the
  // module store, so the two sibling components stay in sync without a Provider.
  const mobileOpen = useDrawerOpen();

  const closeMobile = () => drawerStore.close();

  /** Render one navigation entry as a RouterLink-backed, a11y-correct list item. */
  const renderItem = (item: NavItem) => {
    const Icon = item.icon;
    const selected = pathname === item.path;
    return (
      <ListItem key={item.path} disablePadding>
        <ListItemButton
          component={RouterLink}
          to={item.path}
          selected={selected}
          aria-current={selected ? 'page' : undefined}
          onClick={closeMobile}
        >
          <ListItemIcon>
            <Icon />
          </ListItemIcon>
          <ListItemText primary={item.label} />
        </ListItemButton>
      </ListItem>
    );
  };

  // Shared content for both the temporary (mobile) and permanent (desktop)
  // Drawers: a Toolbar spacer, the canonical 10 main-menu items, a divider, then
  // the separate Admin flow — all inside a single labeled <nav> landmark.
  const navContent = (
    <>
      {/* Spacer offsetting the fixed AppBar so the list starts below it. */}
      <Toolbar />
      <Box
        component="nav"
        id="primary-navigation"
        aria-label="Primary navigation"
        sx={{ overflow: 'auto' }}
      >
        <List>{MAIN_MENU_ITEMS.map(renderItem)}</List>
        <Divider />
        {/* Admin is a distinct administrative flow, not one of the canonical 10. */}
        <List>{renderItem(ADMIN_NAV_ITEM)}</List>
      </Box>
    </>
  );

  return (
    <>
      {/* Temporary Drawer for xs/sm — opened by the AppBar toggle (Header), which
          shares state through the drawer store; dismissible via backdrop/Escape. */}
      <Drawer
        variant="temporary"
        open={mobileOpen}
        onClose={closeMobile}
        ModalProps={{ keepMounted: true }}
        sx={(theme) => ({
          display: { xs: 'block', md: 'none' },
          '& .MuiDrawer-paper': {
            width: theme.spacing(DRAWER_WIDTH_UNITS),
            boxSizing: 'border-box',
          },
        })}
      >
        {navContent}
      </Drawer>

      {/* Permanent Drawer for md+ — the always-visible desktop rail. */}
      <Drawer
        variant="permanent"
        sx={(theme) => ({
          display: { xs: 'none', md: 'block' },
          width: theme.spacing(DRAWER_WIDTH_UNITS),
          flexShrink: 0,
          '& .MuiDrawer-paper': {
            width: theme.spacing(DRAWER_WIDTH_UNITS),
            boxSizing: 'border-box',
          },
        })}
      >
        {navContent}
      </Drawer>
    </>
  );
}
