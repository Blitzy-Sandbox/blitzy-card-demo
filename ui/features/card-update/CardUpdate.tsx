/**
 * Credit Card Update screen — [DEFERRED] placeholder (zero business logic).
 *
 * Legacy provenance: main-menu option 5 "Credit Card Update" -> program COCRDUPC.
 * [SRC: COCRDUPC | COMEN02Y.cpy:L49-L53]
 *
 * Deferred per AAP §0.7: only the Card Detail read-render tracer is live; every
 * other screen (including Card Update) is a typed placeholder. This screen does
 * NOT fetch data, does NOT use auth, and MUST NOT call the BFF.
 */
import { useParams } from 'react-router-dom';
import { PageContainer } from '../../components/PageContainer';
import { Empty } from '../../components/Empty';

export default function CardUpdate() {
  // Read the route param for display context only (no data fetching / no BFF call).
  const { cardNumber } = useParams();

  return (
    <PageContainer
      title="Credit Card Update"
      subtitle={cardNumber ? `Card ${cardNumber}` : undefined}
    >
      <Empty message="Credit Card Update is not yet implemented (deferred)." />
    </PageContainer>
  );
}
