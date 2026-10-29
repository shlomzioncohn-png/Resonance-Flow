import type { ReactNode } from 'react';

export interface Column<T> {
  /** Unique column id — also the sort key reported through `onSortChange`. */
  id: string;
  header: string;
  /** What the cell shows. All domain-specific content belongs here. */
  render: (row: T) => ReactNode;
  align?: 'left' | 'right' | 'center';
  width?: number | string;
  sortable?: boolean;
}

export interface DataTableSort {
  columnId: string;
  direction: 'asc' | 'desc';
}

export interface DataTablePagination {
  /** Zero-based. */
  page: number;
  pageSize: number;
  totalCount: number;
  onPageChange: (page: number) => void;
  onPageSizeChange?: (pageSize: number) => void;
}

export interface DataTableProps<T> {
  columns: Column<T>[];
  rows: T[];
  getRowId: (row: T) => string;
  ariaLabel: string;

  loading?: boolean;
  error?: string | null;
  onRetry?: () => void;
  emptyTitle?: string;
  emptyDescription?: string;

  /** Controlled: the table only reports the requested sort, the page decides. */
  sort?: DataTableSort;
  onSortChange?: (sort: DataTableSort) => void;

  /** Controlled. Omit for a table without a pagination bar. */
  pagination?: DataTablePagination;

  /**
   * Makes the whole row clickable. A mouse shortcut only: put a real button or link in a cell
   * for keyboard and screen-reader users, since the row itself is not focusable.
   */
  onRowClick?: (row: T) => void;
}
