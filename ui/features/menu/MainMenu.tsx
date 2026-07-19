import { Card, CardActionArea, CardContent, Grid, Stack, Typography } from '@mui/material';
import { Link } from 'react-router-dom';
import { PageContainer } from '../../components/PageContainer';
import { Empty } from '../../components/Empty';

/**
 * MainMenu — post-login landing screen ([DEFERRED] placeholder).
 *
 * Provenance: legacy CardDemo main menu [SRC: COMEN02Y.cpy:L19-L84] (10 options)
 * and the main-menu screen [app/bms/COMEN01.bms] (title "Main Menu").
 *
 * [DEFERRED]: ZERO business logic. This screen does NOT call the BFF (it must NOT
 * call the deferred getMenu stub), uses no auth/data hooks, and imports no
 * generated client. The option tiles below are pure client-side router links
 * (react-router <Link>) — navigation only. The PRIMARY navigation Drawer lives in
 * ui/app/layout/NavMenu; this landing content is supplementary.
 */
interface MenuOption {
  label: string;
  to: string;
}

// Derived 1:1 from the legacy 10-option main menu [SRC: COMEN02Y.cpy:L19-L84].
// Paths match ui/app/routes.tsx and the NavMenu Drawer; parameterized routes use
// the seeded tracer values (card 0500024453765740, account 50 — see
// db/migration/V2__seed_tracer.sql) so the placeholder screens render. Option 4
// "Credit Card View" is the LIVE TRACER.
const MENU_OPTIONS: readonly MenuOption[] = [
  { label: 'Account View', to: '/accounts/50' },
  { label: 'Account Update', to: '/accounts/50/edit' },
  { label: 'Credit Card List', to: '/cards' },
  { label: 'Credit Card View', to: '/cards/0500024453765740' },
  { label: 'Credit Card Update', to: '/cards/0500024453765740/edit' },
  { label: 'Transaction List', to: '/transactions' },
  { label: 'Transaction View', to: '/transactions/1' },
  { label: 'Transaction Add', to: '/transactions/new' },
  { label: 'Transaction Reports', to: '/reports' },
  { label: 'Bill Payment', to: '/bill-payment' },
];

export default function MainMenu() {
  return (
    <PageContainer title="Main Menu">
      <Stack spacing={3}>
        <Typography variant="body1">
          Welcome to CardDemo. Select an option below, or use the navigation menu.
        </Typography>
        <Empty message="This is the CardDemo main menu. Screen functionality is deferred; use the navigation menu to open the Card Detail tracer." />
        <Grid container spacing={2}>
          {MENU_OPTIONS.map((option) => (
            <Grid key={option.to} size={{ xs: 12, sm: 6, md: 4 }}>
              <Card variant="outlined">
                <CardActionArea component={Link} to={option.to}>
                  <CardContent>
                    <Typography variant="subtitle1">{option.label}</Typography>
                  </CardContent>
                </CardActionArea>
              </Card>
            </Grid>
          ))}
        </Grid>
      </Stack>
    </PageContainer>
  );
}
