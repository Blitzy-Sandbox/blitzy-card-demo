import { PageContainer } from '../../components/PageContainer';
import { DataTable, type DataTableColumn } from '../../components/DataTable';
import { DeferredNotice } from '../../components/DeferredNotice';

/**
 * Transaction List — [DEFERRED] placeholder (zero business logic).
 *
 * Provenance: [SRC: COTRN00C | COMEN02Y.cpy:L55-L59]
 *   Legacy main-menu option 6 "Transaction List" -> program COTRN00C.
 *   Column shapes from app/bms/COTRN00.bms (List Transactions screen):
 *   Transaction ID (16), Date (8), Description (26), Amount (12).
 *
 * This is a list-shaped placeholder. It renders the shared core-MUI DataTable
 * with an EMPTY row set and a deferred empty message. It does NOT fetch data,
 * import the generated client, use auth, or call the BFF (AAP §0.1.2, §0.7.2).
 */

/**
 * Row shape for the Transaction List table. Local to this file (never exported):
 * it exists only to strongly type the column definitions and the (currently
 * empty) row set so the shared, generic {@link DataTable} call is type-safe.
 * The four string fields mirror the legacy `COTRN00.bms` data columns.
 */
interface TransactionListRow {
  /** Legacy `Transaction ID` column (16 chars). */
  transactionId: string;
  /** Legacy `Date` column (8 chars). */
  date: string;
  /** Legacy `Description` column (26 chars). */
  description: string;
  /** Legacy `Amount` column (12 chars); right-aligned per currency convention. */
  amount: string;
}

/**
 * Column definitions for the list-shaped placeholder, typed against
 * {@link TransactionListRow}. Declared once at module scope (stable identity)
 * and passed straight to {@link DataTable}. The legacy `Sel` selection marker
 * is intentionally omitted — it was CICS navigation only, not meaningful data.
 */
const columns: DataTableColumn<TransactionListRow>[] = [
  { id: 'transactionId', label: 'Transaction ID', field: 'transactionId' },
  { id: 'date', label: 'Date', field: 'date' },
  { id: 'description', label: 'Description', field: 'description' },
  { id: 'amount', label: 'Amount', align: 'right', field: 'amount' },
];

/**
 * Transaction List feature screen (default export — the `/transactions` route
 * in `ui/app/routes.tsx` imports this component as its default).
 *
 * Renders the standard page frame ({@link PageContainer}) around the shared
 * {@link DataTable} with an explicitly empty row set. The explicit generic
 * argument `<TransactionListRow>` pins the table's row type `T`; without it the
 * empty `rows={[]}` literal would infer as `never[]` and conflict with the
 * typed `columns`/`getRowKey`. As a `[DEFERRED]` placeholder this component
 * holds no state, performs no I/O, and calls no service.
 */
export default function TransactionList() {
  return (
    <PageContainer title="Transaction List">
      {/* Canonical [DEFERRED] marker; the table below shows the list SHAPE only. */}
      <DeferredNotice feature="Transaction List" sx={{ mb: 3 }} />
      <DataTable<TransactionListRow>
        columns={columns}
        rows={[]}
        getRowKey={(row) => row.transactionId}
        emptyMessage="No transactions to display (deferred)."
        ariaLabel="transaction list"
      />
    </PageContainer>
  );
}
