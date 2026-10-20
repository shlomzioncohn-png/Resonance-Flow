'use client';

import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward';
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward';
import RemoveIcon from '@mui/icons-material/Remove';
import Box from '@mui/material/Box';
import Card from '@mui/material/Card';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { SparkLineChart } from '@mui/x-charts/SparkLineChart';
import type { StatusTone } from '../StatusBadge';

export interface KpiTrend {
  /** Change vs the previous period, in percent: 8 means +8%, -3 means -3%. */
  delta: number;
  /**
   * Which direction is an improvement. Defaults to 'up'. Use 'down' for
   * counts where less is better, e.g. blocked tasks.
   */
  goodDirection?: 'up' | 'down';
}

/** Hides text visually but keeps it for screen readers. */
const srOnly = {
  position: 'absolute',
  width: 1,
  height: 1,
  overflow: 'hidden',
  clip: 'rect(0 0 0 0)',
  whiteSpace: 'nowrap',
} as const;

function TrendIndicator({ delta, goodDirection = 'up' }: KpiTrend) {
  const theme = useTheme();
  const direction = delta > 0 ? 'up' : delta < 0 ? 'down' : 'flat';
  const color =
    direction === 'flat'
      ? theme.palette.text.secondary
      : direction === goodDirection
        ? theme.palette.status.healthy.text
        : theme.palette.status.critical.text;
  const Icon =
    direction === 'up' ? ArrowUpwardIcon : direction === 'down' ? ArrowDownwardIcon : RemoveIcon;
  const percent = Math.abs(delta);
  const spokenText =
    direction === 'flat'
      ? 'No change from previous period'
      : `${direction === 'up' ? 'Up' : 'Down'} ${percent}% from previous period`;

  return (
    <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center', color }}>
      <Icon fontSize="small" aria-hidden />
      <Typography variant="body2" component="span" sx={{ fontWeight: 600 }} aria-hidden>
        {direction === 'up' ? '+' : direction === 'down' ? '−' : ''}
        {percent}%
      </Typography>
      <Box component="span" sx={srOnly}>
        {spokenText}
      </Box>
    </Stack>
  );
}

const SPARKLINE_HEIGHT = 32;

interface SparklineProps {
  data: number[];
  color: string;
  label?: string;
}

function Sparkline({ data, color, label }: SparklineProps) {
  if (data.length < 2) return null;

  return (
    <Box role={label ? 'img' : undefined} aria-label={label} aria-hidden={label ? undefined : true}>
      <SparkLineChart
        data={data}
        height={SPARKLINE_HEIGHT}
        color={color}
        margin={2}
        showTooltip
        showHighlight
      />
    </Box>
  );
}

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
  /** Change vs the previous period, shown next to the value. */
  trend?: KpiTrend;
  /** Recent values, oldest first, drawn as a small line chart at the bottom of the tile. */
  sparkline?: number[];
  /**
   * Screen-reader description of the sparkline, e.g. "Blocked tasks over the
   * last 7 days, falling from 16 to 12". Without it the line is decorative
   * and hidden from screen readers.
   */
  sparklineLabel?: string;
}

export default function KpiTile({
  value,
  label,
  tone,
  subtitle,
  trend,
  sparkline,
  sparklineLabel,
}: KpiTileProps) {
  const theme = useTheme();
  const color = tone === 'neutral' ? theme.palette.text.primary : theme.palette.status[tone].main;

  return (
    <Card sx={{ px: 3, py: 2.5, flex: 1 }}>
      <Stack spacing={0.5}>
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'baseline' }}>
          <Typography variant="h3" component="p" sx={{ fontWeight: 700, color }}>
            {value}
          </Typography>
          {trend && <TrendIndicator {...trend} />}
        </Stack>
        <Typography variant="body2" color="text.secondary">
          {label}
        </Typography>
        {subtitle && (
          <Typography variant="caption" color="text.secondary">
            {subtitle}
          </Typography>
        )}
        {sparkline && <Sparkline data={sparkline} color={color} label={sparklineLabel} />}
      </Stack>
    </Card>
  );
}
