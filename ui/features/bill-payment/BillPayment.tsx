/**
 * BillPayment.tsx — Bill Payment feature screen ([DEFERRED] placeholder).
 *
 * One of the ~15 non-tracer placeholder screens in the CardDemo walking-skeleton
 * React 19 + TypeScript SPA. Its sole purpose is to make the full navigation
 * surface present (main-menu option 10 "Bill Payment") while only the Card
 * Detail tracer is live end-to-end. It carries ZERO business logic: no data
 * fetching, no generated API client, no auth, and NO BFF call. A typed
 * placeholder is the correct outcome; inventing a bill-payment implementation
 * would be a failure (AAP §0.1.2, §0.7.2, §0.8).
 *
 * Design-system compliance (AAP §0.5): composed only from the shared, MUI-core
 * `PageContainer` + `DeferredNotice` components — no raw HTML, no direct MUI
 * primitives, no MUI X, and no hardcoded colors/spacing.
 *
 * Provenance: [SRC: COBIL00C | ACCTDAT] — legacy main-menu option 10 "Bill
 * Payment" invokes CICS program `COBIL00C` (app/cbl/COBIL00C.cbl; BMS map
 * app/bms/COBIL00.bms), which pays the balance against the account dataset
 * `ACCTDAT`. REFERENCE only; no legacy logic or fields are ported.
 *
 * @returns the deferred Bill Payment placeholder screen.
 */
import { PageContainer } from '../../components/PageContainer';
import { DeferredNotice } from '../../components/DeferredNotice';

export default function BillPayment() {
  return (
    <PageContainer title="Bill Payment">
      <DeferredNotice feature="Bill Payment" />
    </PageContainer>
  );
}
