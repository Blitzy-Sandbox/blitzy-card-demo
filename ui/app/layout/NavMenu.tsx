import {
  Box,
  Drawer,
  List,
  ListItem,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Toolbar,
} from '@mui/material';
import AccountBalanceIcon from '@mui/icons-material/AccountBalance';
import ManageAccountsIcon from '@mui/icons-material/ManageAccounts';
import CreditCardIcon from '@mui/icons-material/CreditCard';
import CreditScoreIcon from '@mui/icons-material/CreditScore';
import EditIcon from '@mui/icons-material/Edit';
import ReceiptLongIcon from '@mui/icons-material/ReceiptLong';
import ReceiptIcon from '@mui/icons-material/Receipt';
import AddCardIcon from '@mui/icons-material/AddCard';
import AssessmentIcon from '@mui/icons-material/Assessment';
import PaymentIcon from '@mui/icons-material/Payment';
import AdminPanelSettingsIcon from '@mui/icons-material/AdminPanelSettings';
import type { SvgIconComponent } from '@mui/icons-material';
import { useLocation, useNavigate } from 'react-router-dom';

/**
 * Width of the permanent navigation Drawer, expressed in MUI spacing units
 * (8px base): 30 * 8px = 240px. Kept token-derived (theme.spacing) per the
 * design-system rule that every value resolves to a theme token.
 */
const DRAWER_WIDTH_UNITS = 30;

interface NavItem {
  label: string;
  path: string;
  icon: SvgIconComponent;
}

/**
 * Primary navigation, derived 1:1 from the legacy CardDemo main menu
 * [app/cpy/COMEN02Y.cpy] (10 options), plus an Admin entry. Option 4
 * "Credit Card View" is the LIVE TRACER and links to the seeded card
 * 0500024453765740 (db/migration/V2__seed_tracer.sql). Parameterized routes use
 * concrete demo values (seeded account 50 / seeded card) so placeholder screens
 * render.
 */
const NAV_ITEMS: readonly NavItem[] = [
  { label: 'Account View', path: '/accounts/50', icon: AccountBalanceIcon },
  { label: 'Account Update', path: '/accounts/50/edit', icon: ManageAccountsIcon },
  { label: 'Credit Card List', path: '/cards', icon: CreditCardIcon },
  { label: 'Credit Card View', path: '/cards/0500024453765740', icon: CreditScoreIcon },
  { label: 'Credit Card Update', path: '/cards/0500024453765740/edit', icon: EditIcon },
  { label: 'Transaction List', path: '/transactions', icon: ReceiptLongIcon },
  { label: 'Transaction View', path: '/transactions/1', icon: ReceiptIcon },
  { label: 'Transaction Add', path: '/transactions/new', icon: AddCardIcon },
  { label: 'Transaction Reports', path: '/reports', icon: AssessmentIcon },
  { label: 'Bill Payment', path: '/bill-payment', icon: PaymentIcon },
  { label: 'Admin', path: '/admin', icon: AdminPanelSettingsIcon },
];

export function NavMenu() {
  const navigate = useNavigate();
  const { pathname } = useLocation();

  return (
    <Drawer
      variant="permanent"
      sx={(theme) => ({
        width: theme.spacing(DRAWER_WIDTH_UNITS),
        flexShrink: 0,
        '& .MuiDrawer-paper': {
          width: theme.spacing(DRAWER_WIDTH_UNITS),
          boxSizing: 'border-box',
        },
      })}
    >
      {/* Spacer offsetting the fixed AppBar so the list starts below the header. */}
      <Toolbar />
      <Box sx={{ overflow: 'auto' }}>
        <List>
          {NAV_ITEMS.map((item) => {
            const Icon = item.icon;
            return (
              <ListItem key={item.path} disablePadding>
                <ListItemButton
                  selected={pathname === item.path}
                  onClick={() => navigate(item.path)}
                >
                  <ListItemIcon>
                    <Icon />
                  </ListItemIcon>
                  <ListItemText primary={item.label} />
                </ListItemButton>
              </ListItem>
            );
          })}
        </List>
      </Box>
    </Drawer>
  );
}
