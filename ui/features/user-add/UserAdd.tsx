/**
 * UserAdd.tsx — Add User feature screen ([DEFERRED] placeholder).
 *
 * One of the ~15 non-tracer placeholder screens in the CardDemo walking-skeleton
 * React 19 + TypeScript SPA, mounted at `/users/new` so the admin navigation
 * surface is complete while only the Card Detail tracer is live. It carries ZERO
 * business logic: no data fetching, no generated API client, no auth, no form
 * submission, and NO BFF call. A typed placeholder is the correct outcome;
 * inventing an add-user implementation would be a failure (AAP §0.1.2, §0.7.2,
 * §0.8).
 *
 * Design-system compliance (AAP §0.5): composed only from the shared, MUI-core
 * `PageContainer` + `DeferredNotice` components — no raw HTML, no direct MUI
 * primitives, no MUI X, and no hardcoded colors/spacing.
 *
 * Provenance: [SRC: COUSR01C | COUSR01.bms] — legacy Add-User CICS program
 * `COUSR01C` (app/cbl/COUSR01C.cbl) and its screen map app/bms/COUSR01.bms
 * ("Add User"), reached from the Admin Menu. REFERENCE only; none of the legacy
 * fields (User ID / First Name / Last Name / Password / User Type over
 * SEC-USER-DATA) are ported — the screen is deferred.
 *
 * @returns the deferred Add User placeholder screen.
 */
import { PageContainer } from '../../components/PageContainer';
import { DeferredNotice } from '../../components/DeferredNotice';

export default function UserAdd() {
  return (
    <PageContainer title="Add User">
      <DeferredNotice feature="Add User" />
    </PageContainer>
  );
}
