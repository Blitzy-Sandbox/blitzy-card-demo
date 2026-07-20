/**
 * CardDetail.tsx — Card Detail screen: the VISIBLE half of THE ONE LIVE
 * VERTICAL TRACER SLICE of the CardDemo walking skeleton.
 *
 * This is the single fully-functional screen of the skeleton (AAP §0.1.1,
 * §0.6.3). Every other feature screen is a typed placeholder; this one renders
 * real data with real loading / empty / error states. It owns NO data-fetching
 * logic itself — all state comes from the {@link useCardDetail} hook, which
 * performs the one real network hop of the entire tracer and binds ONLY to the
 * BFF:
 *
 *   UI (this screen ← useCardDetail) → BFF (GET /api/cards/{cardNumber})
 *     → card-svc (GET /cards/{cardNumber}) → Oracle FREEPDB1 (seeded CARD row)
 *
 * The screen selects exactly one visual state from the hook snapshot, in the
 * mutually-exclusive precedence:
 *   loading → error (non-404: 500 / network) → notFound / empty (404 or no row)
 *     → detail (populated Card).
 * The screen title stays visible in all four states because the state switch is
 * rendered inside {@link PageContainer}.
 *
 * Design system (AAP §0.5, non-negotiable): composed exclusively from
 * `@mui/material` CORE primitives + the shared `ui/components` library; no
 * `@mui/x-*`, no raw HTML where a MUI equivalent exists, and no hardcoded CSS
 * values — spacing flows through the theme scale (`Grid`/`Stack` `spacing`) and
 * color through the `text.secondary` theme token. NOTE (MUI v9): the field-label
 * color is applied via `sx={{ color: 'text.secondary' }}` rather than the
 * `Typography` `color` prop — in v9 the `color` prop no longer resolves dotted
 * palette paths (`text.secondary`) and silently leaves the text at
 * `text.primary`; `sx` resolves it to `var(--mui-palette-text-secondary)`. This
 * matches the shared components (PageContainer / Loading / Empty). MUI v9
 * conventions: `Grid` uses the `size` prop (never the removed `item`/`xs`/`md`
 * props).
 *
 * Routing contract: the router imports this module's DEFAULT export
 * (`import CardDetail from '../features/card-detail/CardDetail'`) at route
 * `/cards/:cardNumber`; the `cardNumber` route param is read with `useParams`
 * and passed straight into the hook.
 *
 * Provenance (legacy reference, read-only — never modified):
 *   [SRC: COCRDSLC | CARDDAT]     — legacy Credit Card View program reading the
 *                                   CARDDAT VSAM dataset (main-menu option 4).
 *   [SRC: COCRDSL.bms:L75-L152]   — Card Detail BMS map: screen title
 *                                   "View Credit Card Detail" (L78) and the five
 *                                   labeled fields (L79-L136).
 *   [SRC: CVACT02Y.cpy:L4-L10]    — CARD-RECORD copybook whose fields map to the
 *                                   CardDetail DTO (CARD-CVV-CD intentionally
 *                                   excluded from the read DTO).
 *   [SRC: COMEN02Y.cpy:L43-L47]   — main-menu option 4 "Credit Card View" routes
 *                                   here (the nav item lives in ui/app/layout).
 */
import { useParams } from 'react-router-dom';
import { Card, CardContent, CardHeader, Divider, Grid, Stack, Typography } from '@mui/material';

import { PageContainer } from '../../components/PageContainer';
import { StatusChip } from '../../components/StatusChip';
import { Loading } from '../../components/Loading';
import { Empty } from '../../components/Empty';
// Aliased to `ErrorState` so it does not shadow the global `Error` constructor
// within this module (the shared component's module-scoped export is `Error`).
import { Error as ErrorState } from '../../components/Error';

import { useCardDetail } from './useCardDetail';

/**
 * Card Detail tracer screen (default export — matches the router's default
 * import and the `/cards/:cardNumber` route).
 *
 * Reads the `cardNumber` route param, delegates all fetching / state to
 * {@link useCardDetail}, and renders the resulting loading / error / empty /
 * detail state inside {@link PageContainer}.
 */
export default function CardDetail() {
  // `cardNumber` is `string | undefined` (missing param → undefined), which is
  // exactly the hook's parameter type; it is passed straight through.
  const { cardNumber } = useParams();
  const { data, loading, error, notFound } = useCardDetail(cardNumber);

  /**
   * Select and render exactly one of the four mutually-exclusive states. A
   * helper (rather than nested ternaries) keeps the precedence explicit and
   * lets TypeScript narrow `data` from `CardDetail | null` to `CardDetail` in
   * the detail branch via the `data == null` early return.
   */
  const renderContent = () => {
    // 1) LOADING — the single BFF request is in flight.
    if (loading) {
      return <Loading variant="skeleton" />;
    }

    // 2) ERROR — any non-404 failure (500 / network / unknown) surfaced by the
    //    hook. 404s are reported separately via `notFound` (handled below).
    if (error) {
      return <ErrorState error={error} title="Unable to load card" />;
    }

    // 3) EMPTY — HTTP 404 or no card number supplied. The `data == null` guard
    //    also narrows `data` to non-null for the detail branch under strict
    //    null checks.
    if (notFound || data == null) {
      return <Empty message="Card not found." />;
    }

    // 4) DETAIL — a card row is present. Render the five fields sourced from the
    //    legacy Card Detail map [COCRDSL.bms:L79-L136] / CARD-RECORD
    //    [CVACT02Y.cpy:L4-L10]. The CVV is intentionally NOT rendered.
    return (
      <Card>
        <CardHeader title="Credit Card Detail" />
        <Divider />
        <CardContent>
          <Grid container spacing={2}>
            {/* Account Number — CARD-ACCT-ID 9(11) / ACCTSID */}
            <Grid size={{ xs: 12, sm: 6 }}>
              <Stack spacing={0.5} sx={{ alignItems: 'flex-start' }}>
                <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                  Account Number
                </Typography>
                <Typography variant="body1">{data.accountId}</Typography>
              </Stack>
            </Grid>

            {/* Card Number — CARD-NUM X(16) natural key / CARDSID */}
            <Grid size={{ xs: 12, sm: 6 }}>
              <Stack spacing={0.5} sx={{ alignItems: 'flex-start' }}>
                <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                  Card Number
                </Typography>
                <Typography variant="body1">{data.cardNumber}</Typography>
              </Stack>
            </Grid>

            {/* Name on Card — CARD-EMBOSSED-NAME X(50) / CRDNAME */}
            <Grid size={{ xs: 12, sm: 6 }}>
              <Stack spacing={0.5} sx={{ alignItems: 'flex-start' }}>
                <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                  Name on Card
                </Typography>
                <Typography variant="body1">{data.embossedName}</Typography>
              </Stack>
            </Grid>

            {/* Active Status — CARD-ACTIVE-STATUS X(01) Y/N / CRDSTCD */}
            <Grid size={{ xs: 12, sm: 6 }}>
              <Stack spacing={0.5} sx={{ alignItems: 'flex-start' }}>
                <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                  Active Status
                </Typography>
                <StatusChip status={data.activeStatus} />
              </Stack>
            </Grid>

            {/* Expiry — CARD-EXPIRAION-DATE X(10) / EXPMON + EXPYEAR */}
            <Grid size={{ xs: 12, sm: 6 }}>
              <Stack spacing={0.5} sx={{ alignItems: 'flex-start' }}>
                <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                  Expiry
                </Typography>
                <Typography variant="body1">{data.expiryDate}</Typography>
              </Stack>
            </Grid>
          </Grid>
        </CardContent>
      </Card>
    );
  };

  return (
    <PageContainer title="View Credit Card Detail">{renderContent()}</PageContainer>
  );
}
