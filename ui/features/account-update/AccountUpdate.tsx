import { useParams } from 'react-router-dom';
import { PageContainer } from '../../components/PageContainer';
import { DeferredNotice } from '../../components/DeferredNotice';

/**
 * AccountUpdate — Account Update feature screen (`[DEFERRED]` placeholder).
 *
 * Part of the CardDemo walking-skeleton React 19 + TypeScript SPA. This screen
 * exists so the full navigation surface is present, but it carries ZERO
 * business logic per the run contract's deferred-scope directive (AAP §0.1.2,
 * §0.6.3, §0.7.2): it neither fetches data, imports the generated API client,
 * consults auth, nor calls the BFF. A typed placeholder is the CORRECT outcome
 * for a deferred screen; inventing functionality would be a failure.
 *
 * It renders the standard shared page frame ({@link PageContainer}) wrapping a
 * shared deferred notice ({@link DeferredNotice}) that renders the canonical
 * `[DEFERRED]` marker. The route param `accountId` (from the route
 * `/accounts/:accountId/edit`) is read purely for display context and is
 * surfaced as the page subtitle — it triggers no fetch or side effect.
 *
 * Design-system compliance (AAP §0.5): this file composes the already-compliant
 * shared components only. It imports no `@mui/material` directly and no MUI X
 * package; it renders no raw HTML, uses no inline styles, and hardcodes no
 * colors/spacing — all layout, typography, and spacing flow through the shared
 * components' theme tokens.
 *
 * Provenance: [SRC: COACTUPC | COMEN02Y.cpy:L31-L35] — legacy main-menu option
 * 2 "Account Update", which invokes CICS program `COACTUPC` (legacy BMS map
 * `app/bms/COACTUP.bms`). Those legacy artifacts are REFERENCE only; the sole
 * value taken from them is the human-readable label "Account Update".
 *
 * @returns The deferred Account Update placeholder screen.
 */
export default function AccountUpdate() {
  // Route param from `/accounts/:accountId/edit`. In react-router v7 this is
  // typed `string | undefined`; the guard below narrows it and cleanly omits
  // the subtitle when the screen is reached without an id. Read for display
  // context only — no fetch, no effect, no business logic.
  const { accountId } = useParams();

  return (
    <PageContainer
      title="Account Update"
      subtitle={accountId ? `Account ${accountId}` : undefined}
    >
      <DeferredNotice feature="Account Update" />
    </PageContainer>
  );
}
