'use client';

import Card from '@mui/material/Card';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import type { StatusTone } from '../StatusBadge';

export interface KpiTileProps {
  /** Raw count or pre-formatted text, e.g. 12, "1,284" or "3.2d". */
  value: number | string;
  label: string;
  /**
   * Workflow-health tone the number is colored by. 'neutral' is a plain
   * count (e.g. "Total projects") and reads as dark text rather than the
   * pale gray `status.neutral` swatch, which would look disabled here.
   */
  tone: StatusTone;
  /** Secondary text shown under the label, e.g. "Last 7 days". */
  subtitle?: string;
}

export default function KpiTile({ value, label, tone, subtitle }: KpiTileProps) {
  const theme = useTheme();
  const color = tone === 'neutral' ? theme.palette.text.primary : theme.palette.status[tone].main;

  return (
    <Card sx={{ px: 3, py: 2.5, flex: 1 }}>
      <Stack spacing={0.5}>
        <Typography variant="h3" component="p" sx={{ fontWeight: 700, color }}>
          {value}
        </Typography>
        <Typography variant="body2" color="text.secondary">
          {label}
        </Typography>
        {subtitle && (
          <Typography variant="caption" color="text.secondary">
            {subtitle}
          </Typography>
        )}
      </Stack>
    </Card>
  );
}
