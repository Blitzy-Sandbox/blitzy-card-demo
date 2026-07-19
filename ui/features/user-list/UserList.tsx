/**
 * User List — [DEFERRED] placeholder (list-shaped).
 * Provenance: [SRC: COUSR00C | COUSR00.bms]
 *
 * Renders the legacy "List Users" column structure with ZERO business logic:
 * no data fetching and NO BFF call, no auth, no local state. A typed placeholder
 * is the correct outcome for a [DEFERRED] screen (AAP §0.1.2, §0.7.2); a
 * hallucinated (invented) implementation is a failure.
 *
 * Layout and styling are supplied entirely by the shared MUI-core components
 * (`PageContainer`, `DataTable`) — this file passes no CSS values, uses no raw
 * HTML, and never touches MUI X. The empty `rows` array drives `DataTable`'s
 * own empty-state row.
 */
import { PageContainer } from '../../components/PageContainer';
import { DataTable, type DataTableColumn } from '../../components/DataTable';

/**
 * Row shape derived from the legacy security-user record `SEC-USER-DATA`
 * [SRC: CSUSR01Y.cpy]. The `SEC-USR-PWD` field is intentionally omitted (a
 * password is never shown in a list) and the legacy `Sel` selection affordance
 * is excluded (no interaction in a zero-logic placeholder).
 */
interface UserRow {
  /** SEC-USR-ID PIC X(08). */
  userId: string;
  /** SEC-USR-FNAME PIC X(20). */
  firstName: string;
  /** SEC-USR-LNAME PIC X(20). */
  lastName: string;
  /** SEC-USR-TYPE PIC X(01). */
  userType: string;
}

/**
 * Columns derived from the legacy "List Users" screen [SRC: COUSR00.bms].
 * Typed as `DataTableColumn<UserRow>[]` so the `field` accessors are constrained
 * to real keys of {@link UserRow} and the generic `DataTable` resolves to
 * `T = UserRow`.
 */
const columns: DataTableColumn<UserRow>[] = [
  { id: 'userId', label: 'User ID', field: 'userId' },
  { id: 'firstName', label: 'First Name', field: 'firstName' },
  { id: 'lastName', label: 'Last Name', field: 'lastName' },
  { id: 'userType', label: 'Type', field: 'userType' },
];

// [DEFERRED]: no data fetch — a typed empty rows array renders the DataTable's
// own empty state. No hook, no useState/useEffect, no generated client.
const rows: UserRow[] = [];

/**
 * User List feature screen (default export — consumed by
 * `ui/app/routes.tsx` as `<Route path="/users" element={<UserList />} />`).
 */
export default function UserList() {
  return (
    <PageContainer title="User List">
      <DataTable<UserRow>
        columns={columns}
        rows={rows}
        getRowKey={(row) => row.userId}
        emptyMessage="No users to display (deferred)."
        ariaLabel="user list"
      />
    </PageContainer>
  );
}
