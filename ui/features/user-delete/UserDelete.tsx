import { PageContainer } from '../../components/PageContainer';
import { DeferredNotice } from '../../components/DeferredNotice';

/**
 * Delete User screen — [DEFERRED] placeholder.
 *
 * Provenance: [SRC: COUSR03C | COUSR03.bms]
 * Legacy source: user-delete program COUSR03C / map COUSR03.bms ("Delete User").
 *
 * Zero business logic for this walking-skeleton run: renders the standard page
 * frame plus a deferred-state message. Does NOT fetch data, import the generated
 * client, use auth, or call the BFF.
 *
 * Routing: wired into ui/app/routes.tsx at `/users/:userId/delete` as an inert
 * [DEFERRED] navigation seam (finding P4-M07) — the route renders this placeholder
 * only; NO delete behavior is implemented (AAP §0.1.2, §0.7.2).
 */
export default function UserDelete() {
  return (
    <PageContainer title="Delete User">
      <DeferredNotice feature="Delete User" />
    </PageContainer>
  );
}
