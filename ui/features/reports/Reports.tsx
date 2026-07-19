import { PageContainer } from '../../components/PageContainer';
import { DeferredNotice } from '../../components/DeferredNotice';

/**
 * Transaction Reports screen — [DEFERRED] placeholder (zero business logic).
 *
 * Provenance: [SRC: CORPT00C | COMEN02Y.cpy:L74-L78]
 * Legacy main-menu option 9 "Transaction Reports" -> program CORPT00C
 * (async batch report-submission screen, app/bms/CORPT00.bms).
 *
 * Reporting is an async-job domain in the modernized skeleton (reporting-svc
 * is a health-exempt async job stub), so this UI screen carries ZERO business
 * logic: no data fetching, no generated client, no auth, and it MUST NOT call
 * the BFF. A typed placeholder is the correct outcome for a [DEFERRED] epic.
 */
export default function Reports() {
  return (
    <PageContainer title="Transaction Reports">
      <DeferredNotice feature="Transaction Reports" />
    </PageContainer>
  );
}
