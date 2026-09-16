'use client'

import Card from '@mui/material/Card'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import type { StatusTone } from '../StatusBadge'

export interface KpiTileProps {
  value: number
  label: string
  /**
   * Workflow-health tone the number is colored by. 'neutral' is a plain
   * count (e.g. "Total projects") and reads as dark text rather than the
   * pale gray `status.neutral` swatch, which would look disabled here.
   */
  tone: StatusTone
}

export default function KpiTile({ value, label, tone }: KpiTileProps) {
  const theme = useTheme()
  const color = tone === 'neutral' ? theme.palette.text.primary : theme.palette.status[tone].main

  return (
    <Card sx={{ px: 3, py: 2.5, flex: 1 }}>
      <Stack spacing={0.5}>
        <Typography variant="h3" component="p" sx={{ fontWeight: 700, color }}>
          {value}
        </Typography>
        <Typography variant="body2" color="text.secondary">
          {label}
        </Typography>
      </Stack>
    </Card>
  )
}
