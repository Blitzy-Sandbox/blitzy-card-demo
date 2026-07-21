/**
 * DataTable — a generic, strongly-typed, pure-presentational table.
 *
 * Shared list/tabular renderer for the CardDemo walking-skeleton UI. Built on
 * the CORE `@mui/material` `Table` family only (never the MUI X data-grid
 * component, which is out of scope per AAP §0.5.4 / §0.7.2). The component is
 * generic over the row
 * type `T`: columns are supplied via typed props and are never hardcoded, so the
 * same table backs every list & placeholder screen.
 *
 * It performs NO data fetching and holds NO business logic — consumers pass in
 * `rows`, `columns`, and a `getRowKey`, and optionally opt into client-side
 * pagination. Custom cells (e.g. a status chip) are supplied via a column
 * `render` callback, keeping this component dependency-free (`depends_on_files`
 * is intentionally empty — the empty-state row is inlined rather than importing
 * a sibling component).
 *
 * Provenance: [SRC: COCRDLIC | CARDDAT] — the tabular shape (Account Number /
 * Card Number / Active) and F7=Backward / F8=Forward paging derive from the
 * legacy Card List screen `app/bms/COCRDLI.bms` (REFERENCE only). Generalized
 * here into a reusable, typed table.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import {
  Box,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  Typography,
} from '@mui/material';
import type { SxProps, Theme } from '@mui/material';

/**
 * Describes a single column of a {@link DataTable}. Generic over the row type
 * `T` so that `field` is constrained to real keys of the row and `render`
 * receives a fully-typed row.
 */
export interface DataTableColumn<T> {
  /** Stable unique column id (also used as the React key for header/cells). */
  id: string;
  /** Header label text. */
  label: string;
  /** Cell alignment. Default 'left'. */
  align?: 'left' | 'right' | 'center';
  /** Simple field accessor (used when `render` is not provided). */
  field?: keyof T;
  /**
   * Custom cell renderer (e.g. return a `<StatusChip/>` for an active column).
   * Takes precedence over `field`.
   */
  render?: (row: T) => ReactNode;
}

/**
 * Props for {@link DataTable}. Generic over the row type `T`.
 */
export interface DataTableProps<T> {
  /** Ordered column definitions rendered as the header and per-row cells. */
  columns: DataTableColumn<T>[];
  /** The rows to render. May be empty (triggers the empty state). */
  rows: T[];
  /** Returns a stable React key per row. */
  getRowKey: (row: T, index: number) => string | number;
  /** Enable client-side pagination via TablePagination. Default false. */
  pagination?: boolean;
  /** Page-size options. Default [5, 10, 25]. */
  rowsPerPageOptions?: number[];
  /** Initial page size. Default 10. */
  initialRowsPerPage?: number;
  /** Compact rows (Table size="small"). Default false. */
  dense?: boolean;
  /** Message when rows is empty. Default 'No records to display.'. */
  emptyMessage?: string;
  /** aria-label for the table (accessibility). */
  ariaLabel?: string;
  /**
   * Optional row-click handler. When provided, rows become interactive: they
   * gain the MUI `hover` affordance and a pointer cursor. When omitted, rows are
   * static and DO NOT show a misleading hover highlight (finding m38).
   */
  onRowClick?: (row: T, index: number) => void;
  /** sx passthrough for the outer container (theme tokens only). */
  sx?: SxProps<Theme>;
}

/**
 * Generic MUI-core table.
 *
 * Declared as a `function` (not an arrow) so the generic parameter `<T>` is not
 * mis-parsed as a JSX element in a `.tsx` file.
 *
 * @typeParam T - The row shape rendered by this table.
 */
export function DataTable<T>({
  columns,
  rows,
  getRowKey,
  pagination = false,
  rowsPerPageOptions = [5, 10, 25],
  initialRowsPerPage = 10,
  dense = false,
  emptyMessage = 'No records to display.',
  ariaLabel = 'data table',
  onRowClick,
  sx,
}: DataTableProps<T>) {
  const [page, setPage] = useState(0);
  const [rowsPerPage, setRowsPerPage] = useState(initialRowsPerPage);

  // Clamp the current page when the row count shrinks (e.g. rows are filtered
  // away or the page size grows) so pagination can never strand the view on an
  // out-of-range page that renders empty (finding m38). `safePage` is derived
  // synchronously for the current render; the effect reconciles stored state so
  // TablePagination never receives an out-of-range `page`.
  const maxPage = pagination
    ? Math.max(0, Math.ceil(rows.length / rowsPerPage) - 1)
    : 0;
  const safePage = Math.min(page, maxPage);

  useEffect(() => {
    if (page > maxPage) {
      setPage(maxPage);
    }
  }, [page, maxPage]);

  // Rows are interactive only when a click handler is supplied (finding m38).
  const isRowInteractive = Boolean(onRowClick);

  // Client-side slice for the current page; when pagination is disabled every
  // row is shown. `count` reported to TablePagination is always the full length.
  const visibleRows = pagination
    ? rows.slice(safePage * rowsPerPage, safePage * rowsPerPage + rowsPerPage)
    : rows;

  // Resolve a single cell: a custom `render` wins; otherwise fall back to the
  // `field` accessor (null/undefined -> empty string, never the literal
  // "null"/"undefined"); with neither, render nothing.
  const renderCell = (col: DataTableColumn<T>, row: T): ReactNode => {
    if (col.render) return col.render(row);
    if (col.field != null) {
      const value = row[col.field];
      return value == null ? '' : String(value);
    }
    return null;
  };

  // --- Horizontal-scroll affordance (P5-UI-01) -------------------------------
  // A wide table (e.g. the 4-column Transaction / User lists) overflows its
  // container at narrow viewports (~357px table inside a 311px container at a
  // 375px viewport). The overflow is correctly CONTAINED (see the TableContainer
  // sx below), but horizontal scrollability was not visibly DISCOVERABLE, so the
  // partially clipped trailing column ("Amount") read as truncated rather than
  // scrollable.
  //
  // Fix: a dynamic edge scroll-shadow. A subtle gradient appears on whichever
  // edge still hides content and fades out once that edge is reached — the
  // universally understood "there is more this way" cue. The container is also
  // exposed as a focusable, labelled scroll region ONLY while it actually
  // overflows, so keyboard users can pan it and assistive tech announces it.
  // Every value resolves to a theme token (`divider`, `spacing`, `primary.main`,
  // `transitions`); `transparent`/`0`/`100%`/`auto` are token-exempt.
  const scrollRef = useRef<HTMLDivElement>(null);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(false);
  const isScrollable = canScrollLeft || canScrollRight;

  const updateScrollShadows = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    const maxScrollLeft = el.scrollWidth - el.clientWidth;
    // 1px tolerance absorbs sub-pixel rounding so a shadow reliably clears at
    // the extremes.
    setCanScrollLeft(el.scrollLeft > 1);
    setCanScrollRight(el.scrollLeft < maxScrollLeft - 1);
  }, []);

  useEffect(() => {
    updateScrollShadows();
    const el = scrollRef.current;
    if (!el) return undefined;
    // Recompute when the container or its content is resized (viewport change,
    // font load, data change) in addition to the onScroll handler below.
    const observer =
      typeof ResizeObserver !== 'undefined'
        ? new ResizeObserver(() => updateScrollShadows())
        : null;
    observer?.observe(el);
    window.addEventListener('resize', updateScrollShadows);
    return () => {
      observer?.disconnect();
      window.removeEventListener('resize', updateScrollShadows);
    };
  }, [updateScrollShadows, columns, rows, dense, rowsPerPage, safePage]);

  return (
    <Box sx={sx}>
      {/* Relative wrapper anchors the edge scroll-shadow overlays (P5-UI-01). */}
      <Box sx={{ position: 'relative' }}>
        <TableContainer
          ref={scrollRef}
          component={Paper}
          onScroll={updateScrollShadows}
          // Expose the container as a focusable, labelled scroll region ONLY
          // while it actually overflows (P5-UI-01), so keyboard users can pan the
          // table and assistive tech announces it — without adding a stray
          // tab-stop when the table already fits.
          {...(isScrollable
            ? {
                tabIndex: 0,
                role: 'region',
                'aria-label': `${ariaLabel} (scrollable)`,
              }
            : {})}
          // Constrain the scroll container so a wide table (e.g. the 4-column
          // Transaction / User lists at narrow viewports) scrolls horizontally
          // WITHIN this container instead of forcing the flex `<main>` ancestor
          // wider than the viewport, which produced document-level horizontal
          // overflow at 375px (QA responsive finding on /transactions).
          //
          // Why this exact idiom: `<main>` (the app shell content region) is a
          // flex item with the default `min-width: auto`, so its minimum size is
          // its min-content size — which, without this rule, is the table's
          // intrinsic min-content width. `width: 0` stops that intrinsic width
          // from propagating up (the container contributes a 0 basis), so
          // `<main>` stays viewport-width; `minWidth: '100%'` keeps the container
          // rendered full-width within that now-constrained space; and
          // `overflowX: 'auto'` restores horizontal scrolling for the table.
          //
          // Design-system compliant: `0`, `100%`, and `auto` are structural
          // values (token-exempt); the focus ring resolves to theme tokens
          // (`spacing(0.25)` === 2px, `primary.main`).
          sx={{
            width: 0,
            minWidth: '100%',
            overflowX: 'auto',
            '&:focus-visible': {
              outlineStyle: 'solid',
              outlineWidth: (theme) => theme.spacing(0.25),
              outlineColor: (theme) => theme.palette.primary.main,
              outlineOffset: (theme) => `-${theme.spacing(0.25)}`,
            },
          }}
        >
        <Table size={dense ? 'small' : 'medium'} aria-label={ariaLabel}>
          <TableHead>
            <TableRow>
              {columns.map((col) => (
                <TableCell key={col.id} align={col.align ?? 'left'}>
                  {col.label}
                </TableCell>
              ))}
            </TableRow>
          </TableHead>
          <TableBody>
            {visibleRows.length === 0 ? (
              <TableRow>
                <TableCell colSpan={columns.length} align="center">
                  {/* `text.secondary` via `sx`, not the `color` prop: MUI v9 no
                      longer resolves dotted palette paths through `color` (m36). */}
                  <Typography variant="body2" sx={{ color: 'text.secondary', py: 2 }}>
                    {emptyMessage}
                  </Typography>
                </TableCell>
              </TableRow>
            ) : (
              visibleRows.map((row, index) => (
                <TableRow
                  key={getRowKey(row, index)}
                  // Hover highlight and pointer cursor only when the row is
                  // actually actionable (finding m38); static rows stay flat.
                  hover={isRowInteractive}
                  onClick={onRowClick ? () => onRowClick(row, index) : undefined}
                  sx={isRowInteractive ? { cursor: 'pointer' } : undefined}
                >
                  {columns.map((col) => (
                    <TableCell key={col.id} align={col.align ?? 'left'}>
                      {renderCell(col, row)}
                    </TableCell>
                  ))}
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
        </TableContainer>
        {/* Left edge cue — visible only when scrolled away from the start. */}
        <Box
          aria-hidden
          sx={{
            position: 'absolute',
            top: 0,
            bottom: 0,
            left: 0,
            width: (theme) => theme.spacing(2),
            pointerEvents: 'none',
            background: (theme) =>
              `linear-gradient(to right, ${theme.palette.divider}, transparent)`,
            opacity: canScrollLeft ? 1 : 0,
            transition: (theme) => theme.transitions.create('opacity'),
          }}
        />
        {/* Right edge cue — visible only when more content lies to the right. */}
        <Box
          aria-hidden
          sx={{
            position: 'absolute',
            top: 0,
            bottom: 0,
            right: 0,
            width: (theme) => theme.spacing(2),
            pointerEvents: 'none',
            background: (theme) =>
              `linear-gradient(to left, ${theme.palette.divider}, transparent)`,
            opacity: canScrollRight ? 1 : 0,
            transition: (theme) => theme.transitions.create('opacity'),
          }}
        />
      </Box>
      {pagination ? (
        <TablePagination
          component="div"
          count={rows.length}
          page={safePage}
          onPageChange={(_event, newPage) => setPage(newPage)}
          rowsPerPage={rowsPerPage}
          onRowsPerPageChange={(event) => {
            setRowsPerPage(parseInt(event.target.value, 10));
            setPage(0);
          }}
          rowsPerPageOptions={rowsPerPageOptions}
        />
      ) : null}
    </Box>
  );
}
