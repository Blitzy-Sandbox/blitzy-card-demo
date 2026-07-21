import { Card, CardActionArea, CardContent, Grid, Stack, Typography } from '@mui/material';
import { Link } from 'react-router-dom';
import { PageContainer } from '../../components/PageContainer';
import { DeferredNotice } from '../../components/DeferredNotice';
import { MAIN_MENU_ITEMS } from '../../app/layout/navItems';

/**
 * MainMenu — post-login landing screen ([DEFERRED] placeholder).
 *
 * Provenance: legacy CardDemo main menu [SRC: COMEN01C | COMEN02Y.cpy] (10 options)
 * and the main-menu screen [app/bms/COMEN01.bms] (title "Main Menu").
 *
 * [DEFERRED]: ZERO business logic. This screen does NOT call the BFF (it must NOT
 * call the deferred getMenu stub), uses no auth/data hooks, and imports no
 * generated client. The option tiles below are pure client-side router links
 * (react-router <Link>) — navigation only. The PRIMARY navigation Drawer lives in
 * ui/app/layout/NavMenu; this landing content is supplementary.
 */
// M23: the menu is defined ONCE in ui/app/layout/navItems.ts and shared with the
// NavMenu Drawer. This landing screen renders the same canonical 10 options
// [SRC: COMEN01C | COMEN02Y.cpy] as client-side router links (navigation only —
// no BFF calls, no business logic). Option 4 "Credit Card View" is the LIVE
// TRACER; parameterized routes use the seeded demo values (card 0500024453765740,
// account 50 — db/migration/V2__seed_tracer.sql) so placeholder screens render.

export default function MainMenu() {
  return (
    <PageContainer title="Main Menu">
      <Stack spacing={3}>
        <Typography variant="body1">
          Welcome to CardDemo. Select an option below, or use the navigation menu.
        </Typography>
        <DeferredNotice
          feature="Main Menu"
          detail="Screen functionality is deferred; use the navigation menu (or a tile below) to open the Card Detail tracer."
        />
        <Grid container spacing={2}>
          {MAIN_MENU_ITEMS.map((option) => (
            <Grid key={option.path} size={{ xs: 12, sm: 6, md: 4 }}>
              {/* P5-UI-02: give every tile a UNIFORM height so a wrapping label
                  (e.g. "Transaction Reports" at the md 3-column width) no longer
                  renders taller than its single-line row peers, and the last-row
                  tile ("Bill Payment") matches the rest — fixing the uneven /
                  orphaned rows at 900px. The Card fills its (stretched) grid cell
                  (height 100%); the CardActionArea sets a token-derived minimum
                  height (theme.spacing(11) = 88px, sized to fit a 2-line label) and
                  centers the label vertically, so short and wrapped labels render at
                  the same height. */}
              <Card variant="outlined" sx={{ height: '100%' }}>
                <CardActionArea
                  component={Link}
                  to={option.path}
                  sx={{
                    height: '100%',
                    display: 'flex',
                    alignItems: 'center',
                    minHeight: (theme) => theme.spacing(11),
                  }}
                >
                  <CardContent>
                    {/* Render as an <h2> (component) while keeping the subtitle1
                        visual scale (variant): the page title from PageContainer is
                        an <h1>, so a bare subtitle1 (which MUI maps to <h6> by
                        default) skipped h2–h5 and failed the heading-order rule
                        (QA Issue #2). MUI decouples variant (styling) from component
                        (semantics), so this fixes the a11y order with no visual change. */}
                    <Typography variant="subtitle1" component="h2">
                      {option.label}
                    </Typography>
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
