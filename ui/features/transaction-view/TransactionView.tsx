import { useParams } from 'react-router-dom';
import { PageContainer } from '../../components/PageContainer';
import { Empty } from '../../components/Empty';

/**
 * Transaction View — [DEFERRED] placeholder screen.
 *
 * Legacy provenance: main-menu option 7 "Transaction View" -> COTRN01C
 * [SRC: COTRN01C | COMEN02Y.cpy:L61-L65]; screen app/bms/COTRN01.bms (REFERENCE only).
 *
 * Zero business logic: no data fetching, no generated-client import, no auth,
 * no BFF call. Renders the standard page frame + a deferred empty state only.
 */
export default function TransactionView() {
  // Route param is available for context only (route: /transactions/:transactionId).
  const { transactionId } = useParams<{ transactionId: string }>();

  return (
    <PageContainer
      title="Transaction View"
      subtitle={transactionId ? `Transaction ${transactionId}` : undefined}
    >
      <Empty message="Transaction View is not yet implemented (deferred)." />
    </PageContainer>
  );
}
