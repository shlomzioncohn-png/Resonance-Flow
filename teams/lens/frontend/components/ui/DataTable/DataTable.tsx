import type { ReactNode } from 'react'
import Link from 'next/link'
import Card from '@mui/material/Card'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableContainer from '@mui/material/TableContainer'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'

export interface DataTableColumn<Row> {
  /** Unique key for this column — used as the React key for header/body cells. */
  key: string
  header: string
  render: (row: Row) => ReactNode
  align?: 'left' | 'center' | 'right'
  width?: string | number
}

export interface DataTableProps<Row> {
  columns: DataTableColumn<Row>[]
  rows: Row[]
  getRowId: (row: Row) => string
  /**
   * When provided, each row navigates to this URL on click/Enter. Implemented
   * as a real anchor stretched over the row (not a `<tr onClick>`), so rows
   * stay keyboard- and screen-reader-accessible.
   */
  getRowHref?: (row: Row) => string
  /** Rendered instead of the table when `rows` is empty. */
  emptyState?: ReactNode
}

/**
 * Generic, column-driven data table — the one place row/column rendering
 * lives, so any screen with a list-of-records-in-a-table shape (My Tasks,
 * blocked work, workload, ...) configures columns instead of hand-rolling
 * MUI Table markup. Domain formatting (status chips, date formats, etc.)
 * belongs in each caller's `render` functions, not here.
 */
export default function DataTable<Row>({
  columns,
  rows,
  getRowId,
  getRowHref,
  emptyState,
}: DataTableProps<Row>) {
  if (rows.length === 0 && emptyState) {
    return <>{emptyState}</>
  }

  return (
    <Card>
      <TableContainer sx={{ overflowX: 'auto' }}>
        <Table>
          <TableHead>
            <TableRow>
              {columns.map((column) => (
                <TableCell key={column.key} align={column.align} sx={{ width: column.width }}>
                  {column.header}
                </TableCell>
              ))}
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map((row) => {
              const rowId = getRowId(row)
              const href = getRowHref?.(row)
              const titleCellId = `${rowId}-primary-cell`

              return (
                <TableRow key={rowId} hover={!!href} sx={href ? { position: 'relative' } : undefined}>
                  {columns.map((column, index) => (
                    <TableCell
                      key={column.key}
                      align={column.align}
                      id={index === 0 ? titleCellId : undefined}
                    >
                      {href && index === 0 && (
                        <Link
                          href={href}
                          aria-labelledby={titleCellId}
                          style={{ position: 'absolute', inset: 0 }}
                        />
                      )}
                      {column.render(row)}
                    </TableCell>
                  ))}
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
      </TableContainer>
    </Card>
  )
}
