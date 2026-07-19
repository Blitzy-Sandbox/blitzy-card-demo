/**
 * AccountView.tsx — Account View feature screen ([DEFERRED] placeholder).
 *
 * Part of the CardDemo walking-skeleton React 19 + TypeScript SPA. This screen
 * is one of the ~16 deferred placeholder features under `ui/features/**` that
 * exist so the full navigation surface is present while only the single Card
 * Detail tracer slice is live (AAP §0.1.2, §0.7.2).
 *
 * [DEFERRED] — ZERO business logic. This component:
 *  - performs NO data fetching and issues NO BFF / network calls,
 *  - imports NO generated API client,
 *  - imports NO auth (`useAuth` / `AuthProvider`),
 *  - runs NO data-loading `useEffect` and holds NO backend-derived state,
 *  - renders NONE of the legacy Account View fields (Account Number, balances,
 *    customer details, etc.).
 * A typed placeholder is the correct outcome; inventing functionality here would
 * be a failure. All visual output is composed from the shared, MUI-token-compliant
 * `PageContainer` + `Empty` components — this file adds no raw HTML, no
 * `@mui/material` primitives, no `@mui/x-*`, and no hardcoded colors/spacing.
 *
 * Provenance: [SRC: COACTVWC | COMEN02Y.cpy:L25-L29] — main-menu option 1
 * "Account View" → legacy CICS program `COACTVWC`; legacy screen
 * `app/bms/COACTVW.bms` ("Account Viewer Screen"). REFERENCE only; the legacy
 * fields are intentionally NOT ported (deferred).
 *
 * Route contract: mounted by `ui/app/routes.tsx` via a DEFAULT import at
 * `/accounts/:accountId`, so this module MUST expose a default export named
 * `AccountView`, and the route path param is `accountId`.
 */
import { useParams } from 'react-router-dom';
import { PageContainer } from '../../components/PageContainer';
import { Empty } from '../../components/Empty';

/**
 * AccountView renders the deferred Account View page frame.
 *
 * It reads the `accountId` route param purely for presentational context (echoed
 * in the subtitle) — reading/echoing a route param is presentation, not business
 * logic, and is explicitly permitted for this placeholder. Under strict
 * TypeScript, `useParams()` types `accountId` as `string | undefined`; the
 * conditional `subtitle` expression narrows that union cleanly so the optional
 * `string` prop of {@link PageContainer} is satisfied without a type error.
 *
 * @returns The framed, deferred Account View screen: a `PageContainer` titled
 *          "Account View" wrapping an `Empty` "not yet implemented" message.
 */
export default function AccountView() {
  // [SRC: COACTVWC | COMEN02Y.cpy:L25-L29] — main-menu option 1 "Account View".
  // [DEFERRED] placeholder: the route param is read for context ONLY. No data
  // fetching, no BFF call, no generated client, no auth — zero business logic.
  const { accountId } = useParams();

  return (
    <PageContainer
      title="Account View"
      subtitle={accountId ? `Account ${accountId}` : undefined}
    >
      <Empty message="Account View is not yet implemented (deferred)." />
    </PageContainer>
  );
}
