'use client';

import Card from '@mui/material/Card';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TablePagination from '@mui/material/TablePagination';
import TableRow from '@mui/material/TableRow';
import TableSortLabel from '@mui/material/TableSortLabel';
import EmptyState from '@/components/feedback/EmptyState';
import ErrorState from '@/components/feedback/ErrorState';
import LoadingState from '@/components/feedback/LoadingState';
import type { Column, DataTableProps } from './types';

const PAGE_SIZE_OPTIONS = [10, 20, 50, 100];

/**
 * Generic, column-driven data table — the one place table structure, sort and
 * pagination controls, loading/empty/error states and table accessibility
 * live. It knows how to display rows, never what they are: domain content
 * (status chips, date formats, row actions) belongs in each caller's column
 * `render` functions, not in new props here.
 *
 * Sorting and pagination are controlled — the table only renders the controls
 * and reports what the user asked for; it never sorts, slices or fetches.
 */
export default function DataTable<T>({
  columns,
  rows,
  getRowId,
  ariaLabel,
  loading = false,
  error,
  onRetry,
  emptyTitle,
  emptyDescription,
  sort,
  onSortChange,
  pagination,
}: DataTableProps<T>) {
  if (loading) return <LoadingState />;
  if (error) return <ErrorState description={error} onRetry={onRetry} />;
  if (rows.length === 0) return <EmptyState title={emptyTitle} description={emptyDescription} />;

  const sortDirectionOf = (column: Column<T>) =>
    column.sortable && sort?.columnId === column.id ? sort.direction : false;

  const requestSort = (column: Column<T>) => {
    const isActive = sort?.columnId === column.id;
    onSortChange?.({
      columnId: column.id,
      direction: isActive && sort.direction === 'asc' ? 'desc' : 'asc',
    });
  };

  // The current size is always offered, even when a page uses a non-standard one.
  const pageSizeOptions =
    pagination && !PAGE_SIZE_OPTIONS.includes(pagination.pageSize)
      ? [...PAGE_SIZE_OPTIONS, pagination.pageSize].sort((a, b) => a - b)
      : PAGE_SIZE_OPTIONS;

  return (
    <Card>
      <TableContainer sx={{ overflowX: 'auto' }}>
        <Table aria-label={ariaLabel}>
          <TableHead>
            <TableRow>
              {columns.map((column) => {
                const direction = sortDirectionOf(column);
                return (
                  <TableCell
                    key={column.id}
                    scope="col"
                    align={column.align}
                    sortDirection={direction}
                    sx={{ width: column.width }}
                  >
                    {column.sortable ? (
                      <TableSortLabel
                        active={direction !== false}
                        direction={direction || 'asc'}
                        onClick={() => requestSort(column)}
                      >
                        {column.header}
                      </TableSortLabel>
                    ) : (
                      column.header
                    )}
                  </TableCell>
                );
              })}
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map((row) => (
              <TableRow key={getRowId(row)} hover>
                {columns.map((column) => (
                  <TableCell key={column.id} align={column.align}>
                    {column.render(row)}
                  </TableCell>
                ))}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
      {pagination && (
        <TablePagination
          component="div"
          count={pagination.totalCount}
          page={pagination.page}
          rowsPerPage={pagination.pageSize}
          onPageChange={(_, page) => pagination.onPageChange(page)}
          onRowsPerPageChange={(event) =>
            pagination.onPageSizeChange?.(parseInt(event.target.value, 10))
          }
          rowsPerPageOptions={pagination.onPageSizeChange ? pageSizeOptions : []}
        />
      )}
    </Card>
  );
}
