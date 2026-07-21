// Update User screen — [DEFERRED] placeholder (zero business logic).
//
// One of the CardDemo walking-skeleton's deferred feature screens (AAP §0.6.1
// Group 3, §0.6.3, §0.7.1): it renders the standard page frame plus a
// "not yet implemented (deferred)" message so the full navigation surface
// compiles and routes, while only the Card Detail tracer is live end-to-end.
//
// Provenance: [SRC: COUSR02C | COUSR02.bms] — the legacy Update-User CICS
// program COUSR02C and its BMS map app/bms/COUSR02.bms ("Update User" title at
// L79; fields User ID / First Name / Last Name / Password / User Type over the
// SEC-USER-DATA record app/cpy/CSUSR01Y.cpy). REFERENCE ONLY: this deferred
// placeholder renders none of those fields and holds no business logic.
import { useParams } from 'react-router-dom';
import { PageContainer } from '../../components/PageContainer';
import { DeferredNotice } from '../../components/DeferredNotice';

/**
 * UserUpdate — deferred placeholder for the Update User screen.
 *
 * Rendered by `ui/app/routes.tsx` at `/users/:userId/edit` (imported as the
 * default export). It performs NO data fetching and calls NO service/BFF; the
 * route's `userId` param is read purely for display context and surfaced as the
 * page subtitle. The actual update-user form is intentionally not implemented
 * ([DEFERRED]) — a typed placeholder is the correct outcome for this run.
 *
 * @returns the framed, deferred Update User placeholder screen.
 */
export default function UserUpdate() {
  // Route param is available for context only; this screen performs NO data
  // fetching. `useParams` types `userId` as `string | undefined` under strict
  // null checks, so the guard below narrows it before building the subtitle.
  const { userId } = useParams<{ userId: string }>();

  return (
    <PageContainer
      title="Update User"
      subtitle={userId ? `User ID: ${userId}` : undefined}
    >
      <DeferredNotice feature="Update User" />
    </PageContainer>
  );
}
