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
 * NOTE: Intentionally NOT wired into ui/app/routes.tsx this run. This is a
 * standalone, self-compilable module so a future run can add the
 * `/users/:userId/delete` route without changing this file.
 */
export default function UserDelete() {
  return (
    <PageContainer title="Delete User">
      <DeferredNotice feature="Delete User" />
    </PageContainer>
  );
}
