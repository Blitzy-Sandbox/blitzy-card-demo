/**
 * navItems.ts — the SINGLE typed source of truth for CardDemo navigation.
 *
 * Finding M23: the NavMenu Drawer and the MainMenu landing screen previously
 * each hard-coded their own route list (and the Drawer carried an 11th "Admin"
 * entry the canonical menu does not). Both surfaces now import from here so the
 * menu is defined exactly once.
 *
 * {@link MAIN_MENU_ITEMS} is derived 1:1 from the legacy 10-option main menu
 * [SRC: COMEN01C | COMEN02Y.cpy] (screen [app/bms/COMEN01.bms]). Option 4
 * "Credit Card View" is the LIVE TRACER and links to the seeded card
 * 0500024453765740; parameterized routes use the seeded demo values (account 50 /
 * the seeded card — see db/migration/V2__seed_tracer.sql) so the placeholder
 * screens render.
 *
 * {@link ADMIN_NAV_ITEM} is kept SEPARATE (not one of the 10): Admin is a distinct
 * administrative flow [SRC: COADM01C | COADM01.bms] reached via admin sign-on, so
 * it is exported on its own and rendered in its own section rather than mixed into
 * the canonical ten (M23: "keep Admin in its specified flow").
 *
 * Legacy COBOL/BMS artifacts are REFERENCE only (never modified).
 */
import type { SvgIconComponent } from '@mui/icons-material';
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

/**
 * A single primary-navigation entry. `path` is the react-router route target;
 * `icon` is the MUI icon component rendered in the Drawer list.
 */
export interface NavItem {
  /** Human-readable menu label (matches the legacy option text). */
  label: string;
  /** react-router route path this entry links to. */
  path: string;
  /** MUI icon component rendered beside the label in the Drawer. */
  icon: SvgIconComponent;
}

/**
 * The 10 canonical main-menu options [SRC: COMEN01C | COMEN02Y.cpy].
 * Consumed by both ui/app/layout/NavMenu.tsx and ui/features/menu/MainMenu.tsx.
 */
export const MAIN_MENU_ITEMS: readonly NavItem[] = [
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
];

/**
 * The Admin entry — its OWN administrative flow [SRC: COADM01C | COADM01.bms],
 * deliberately NOT part of the canonical 10 (M23). Rendered in a separate Drawer
 * section so the main menu stays exactly ten items.
 */
export const ADMIN_NAV_ITEM: NavItem = {
  label: 'Admin',
  path: '/admin',
  icon: AdminPanelSettingsIcon,
};
