/**
 * CardList.tsx — Credit Card List feature screen ([DEFERRED] placeholder).
 *
 * Renders the *shape* of the CardDemo credit-card list — an application page
 * carrying the three legacy list columns — but holds ZERO business logic and
 * NEVER calls the BFF. It is a typed placeholder for a functional epic that is
 * deliberately deferred in the walking skeleton (AAP §0.1.2: "a stub returning
 * a typed placeholder is correct; a hallucinated implementation is a failure").
 *
 * Legacy provenance:
 *  - Main-menu option 3 "Credit Card List" -> program COCRDLIC
 *    (app/cpy/COMEN02Y.cpy lines 37-41).
 *  - The list columns (Account Number / Card Number / Active) derive from the
 *    legacy Card Listing screen app/bms/COCRDLI.bms (REFERENCE only).
 * [SRC: COCRDLIC | COMEN02Y.cpy:L37-L41]
 *
 * Design-system compliance (AAP §0.5 — `@mui/material` core only):
 *  - The list is rendered by the shared, core-`Table`-based `DataTable`
 *    (NEVER the out-of-scope MUI X paid data grid component).
 *  - Page chrome (title header + layout) comes from the shared `PageContainer`
 *    (MUI primitives) — no raw HTML layout or heading tags in this file.
 *
 * Route contract (owned by ui/app/routes.tsx):
 *   <Route path="/cards" element={<CardList />} />
 *   import CardList from '../features/card-list/CardList'
 * -> this file MUST default-export a `CardList` component that takes no props.
 *
 * [DEFERRED] — no data fetching, no BFF call, no generated client, no auth,
 * no data-loading state. `rows` is a literal empty array, so the table renders
 * its built-in empty state.
 */
import { PageContainer } from '../../components/PageContainer';
import { DataTable, type DataTableColumn } from '../../components/DataTable';
import { DeferredNotice } from '../../components/DeferredNotice';

/**
 * Shape of a single Credit Card List row.
 *
 * Mirrors the legacy Card Listing columns (app/bms/COCRDLI.bms): Account
 * Number, Card Number, and the Active flag. Declared locally (not imported from
 * a generated client) because this deferred placeholder intentionally fetches
 * no data — the interface exists solely to type the (empty) table so the shared
 * generic `DataTable<T>` stays strongly typed.
 */
interface CardListRow {
  /** Owning account id (legacy 'Account Number' column). */
  accountNumber: string;
  /** 16-digit card number (legacy 'Card Number' column). */
  cardNumber: string;
  /** Active flag, rendered as-is (legacy 'Active' column). */
  activeStatus: string;
}

/**
 * Column definitions for the Credit Card List table.
 *
 * Field-based only (no custom `render`): with an empty row set any renderer
 * would be dead code, so the columns stay minimal. Typed as
 * `DataTableColumn<CardListRow>[]` to pin the table's generic row type to
 * `CardListRow`.
 */
const columns: DataTableColumn<CardListRow>[] = [
  { id: 'accountNumber', label: 'Account Number', field: 'accountNumber' },
  { id: 'cardNumber', label: 'Card Number', field: 'cardNumber' },
  { id: 'activeStatus', label: 'Active', field: 'activeStatus', align: 'center' },
];

/**
 * Credit Card List screen — deferred, list-shaped placeholder.
 *
 * Renders a titled page containing the shared `DataTable` with an empty row
 * set, so the table shows its built-in empty state. Accepts no props (the route
 * renders `<CardList />`) and fetches no data.
 *
 * @returns the Credit Card List placeholder page.
 */
export default function CardList() {
  // Deferred placeholder: no rows, no fetching, no BFF call. The empty array is
  // typed as CardListRow[] so the DataTable generic stays pinned to CardListRow
  // (an untyped [] would let the inferred row type degrade toward `never`).
  const rows: CardListRow[] = [];

  return (
    <PageContainer title="Credit Card List">
      {/* Canonical [DEFERRED] marker; the table below shows the list SHAPE only. */}
      <DeferredNotice feature="Credit Card List" sx={{ mb: 3 }} />
      <DataTable<CardListRow>
        columns={columns}
        rows={rows}
        getRowKey={(row) => row.cardNumber}
        emptyMessage="No cards to display (deferred)."
        ariaLabel="credit card list"
      />
    </PageContainer>
  );
}
