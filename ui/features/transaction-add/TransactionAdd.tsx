/**
 * TransactionAdd.tsx — Transaction Add feature screen ([DEFERRED] placeholder).
 *
 * Occupies the `/transactions/new` route in the CardDemo walking-skeleton SPA
 * with a coherent page frame and a "not yet implemented (deferred)" message,
 * so the full navigation surface exists while the skeleton builds green. This
 * screen is one of the ~16 non-tracer placeholder screens (AAP §0.6.1 Group 3,
 * §0.6.3, §0.7.1) and carries ZERO business logic: no data fetching, no form
 * submission, no auth handling, and no BFF/service calls. A typed placeholder
 * is the CORRECT outcome; a hallucinated Transaction-Add implementation would
 * be a failure (AAP §0.1.2, §0.7.2, §0.8).
 *
 * Design-system compliance (AAP §0.5): this file composes the shared
 * `PageContainer` and `Empty` components — both built from `@mui/material`
 * core primitives and theme tokens — so it is design-system compliant
 * transitively without importing any MUI primitive or hardcoding any value.
 *
 * Provenance: [SRC: COTRN02C | COMEN02Y.cpy:L67-L72] — legacy main-menu
 * option 8 "Transaction Add" → program `COTRN02C`; legacy screen
 * `app/bms/COTRN02.bms`. REFERENCE ONLY (identity/label); never read for
 * behavior — the screen is deferred.
 */
import { PageContainer } from '../../components/PageContainer';
import { Empty } from '../../components/Empty';

/**
 * TransactionAdd renders the deferred Transaction Add placeholder screen.
 *
 * Pure, presentational, and prop-less: the router mounts it as
 * `<TransactionAdd />` for the `/transactions/new` route. It returns the
 * standard page frame with the "Transaction Add" title wrapping a centered,
 * muted deferred-state message.
 *
 * @returns The framed deferred-placeholder page element.
 */
export default function TransactionAdd() {
  return (
    <PageContainer title="Transaction Add">
      <Empty message="Transaction Add is not yet implemented (deferred)." />
    </PageContainer>
  );
}
