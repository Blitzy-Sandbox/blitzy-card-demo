import { useState } from 'react';
import {
  Box,
  Divider,
  Drawer,
  IconButton,
  List,
  ListItem,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Toolbar,
} from '@mui/material';
import MenuIcon from '@mui/icons-material/Menu';
import { Link as RouterLink, useLocation } from 'react-router-dom';
import { ADMIN_NAV_ITEM, MAIN_MENU_ITEMS, type NavItem } from './navItems';

/**
 * NavMenu — the primary CardDemo navigation.
 *
 * Findings addressed:
 *   • M23 — the menu is now sourced ONCE from ui/app/layout/navItems.ts
 *     ({@link MAIN_MENU_ITEMS} = the canonical 10 options; {@link ADMIN_NAV_ITEM}
 *     kept in its own section as a separate administrative flow). No route data
 *     is duplicated here or in MainMenu.
 *   • M21 — responsive: a `temporary` Drawer with an accessible toggle at the
 *     xs/sm breakpoints, and a `permanent` Drawer at md+ (so mobile is no longer
 *     obstructed by an always-open 240px rail).
 *   • M22 — accessibility: the list lives inside a labeled `<nav>` landmark, each
 *     item is a real router link (`ListItemButton` backed by react-router
 *     `Link`), and the current route is marked with `aria-current="page"`.
 *
 * The component is self-contained (the app shell / header is not yet built): it
 * owns its own mobile toggle rather than depending on an AppBar hamburger.
 */

/**
 * Width of the navigation Drawer, expressed in MUI spacing units (8px base):
 * 30 * 8px = 240px. Kept token-derived (theme.spacing) per the design-system
 * rule that every value resolves to a theme token.
 */
const DRAWER_WIDTH_UNITS = 30;

export function NavMenu() {
  const { pathname } = useLocation();
  const [mobileOpen, setMobileOpen] = useState(false);

  const toggleMobile = () => setMobileOpen((open) => !open);
  const closeMobile = () => setMobileOpen(false);

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
      {/* Spacer offsetting the (future) fixed AppBar so the list starts below it. */}
      <Toolbar />
      <Box component="nav" aria-label="Primary navigation" sx={{ overflow: 'auto' }}>
        <List>{MAIN_MENU_ITEMS.map(renderItem)}</List>
        <Divider />
        {/* Admin is a distinct administrative flow, not one of the canonical 10. */}
        <List>{renderItem(ADMIN_NAV_ITEM)}</List>
      </Box>
    </>
  );

  return (
    <>
      {/* Mobile-only toggle (xs/sm). Hidden at md+ where the permanent rail shows.
          Carries an explicit aria-label and aria-expanded for assistive tech. */}
      <IconButton
        aria-label="Open navigation menu"
        aria-expanded={mobileOpen}
        onClick={toggleMobile}
        sx={(theme) => ({
          display: { xs: 'inline-flex', md: 'none' },
          position: 'fixed',
          top: theme.spacing(1),
          left: theme.spacing(1),
          zIndex: theme.zIndex.drawer + 1,
        })}
      >
        <MenuIcon />
      </IconButton>

      {/* Temporary Drawer for xs/sm — opened by the toggle, dismissible. */}
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
