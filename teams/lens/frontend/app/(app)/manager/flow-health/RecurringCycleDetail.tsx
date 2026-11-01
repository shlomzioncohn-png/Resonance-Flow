'use client';

import Box from '@mui/material/Box';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import type { FlowFlag } from '@/services/flowHealthApi';
import { cycleSteps, evidenceNumber, evidenceString } from './flowHealthModel';

const CHART_WIDTH = 360;
const CHART_HEIGHT = 90;
const HIGH_Y = 15;
const LOW_Y = 75;
/** The open last step, and any step too short to see, get at least this much width. */
const MIN_STEP_WIDTH = 24;

function StepChart({ flag }: { flag: FlowFlag }) {
  const theme = useTheme();
  const steps = cycleSteps(flag.evidence);
  if (steps.length === 0) return null;

  const knownDays = steps.reduce((sum, step) => sum + (step.days ?? 0), 0);
  const openWidth = MIN_STEP_WIDTH * 2;
  const usable = CHART_WIDTH - 2 * 8 - openWidth;
  let x = 8;
  const points: string[] = [];
  steps.forEach((step) => {
    const width = step.days === null ? openWidth : Math.max(MIN_STEP_WIDTH, (step.days / (knownDays || 1)) * usable);
    const y = step.state === 'blocked' ? HIGH_Y : LOW_Y;
    points.push(`${x},${y}`, `${x + width},${y}`);
    x += width;
  });

  const summary = steps.map((step) => (step.state === 'blocked' ? 'blocked' : 'released')).join(', then ');
  return (
    <Box
      component="svg"
      role="img"
      aria-label={`Block and release steps: ${summary}`}
      viewBox={`0 0 ${Math.max(CHART_WIDTH, x + 8)} ${CHART_HEIGHT}`}
      sx={{ width: '100%', height: 'auto' }}
    >
      <text x="0" y="10" fontSize="9" fill={theme.palette.text.secondary}>
        Blocked
      </text>
      <text x="0" y="88" fontSize="9" fill={theme.palette.text.secondary}>
        Released
      </text>
      <polyline
        points={points.join(' ')}
        fill="none"
        stroke={theme.palette.status.critical.main}
        strokeWidth="2.5"
        strokeLinejoin="round"
      />
    </Box>
  );
}

function PairView({ flag }: { flag: FlowFlag }) {
  const broken = evidenceString(flag.evidence, 'broken_edge');
  const kept = evidenceString(flag.evidence, 'kept_edge');
  const daysAway = evidenceNumber(flag.evidence, 'days_away');
  const label = (edgeId?: string) => flag.entities.find((entity) => entity.id === edgeId)?.label ?? edgeId ?? '—';
  return (
    <Stack spacing={1}>
      <Typography variant="body2">
        <strong>Removed, then returned:</strong> {label(broken)}
        {daysAway !== undefined && ` (away ${Math.round(daysAway)} days)`}
      </Typography>
      <Typography variant="body2">
        <strong>Stayed active meanwhile:</strong> {label(kept)}
      </Typography>
    </Stack>
  );
}

/**
 * Blocker cycle: a step chart of the edge being blocked and released again and again,
 * or - for two edges of one task - which one came back and which one stayed.
 */
export default function RecurringCycleDetail({ flag }: { flag: FlowFlag }) {
  const transitions = evidenceNumber(flag.evidence, 'transitions');
  const span = evidenceNumber(flag.evidence, 'span_days');
  return (
    <Stack spacing={2}>
      <Box component="section" aria-label="Block and release steps">
        <Typography variant="subtitle2" component="h3" sx={{ mb: 1 }}>
          Block and release
        </Typography>
        {flag.entityType === 'edge_pair' ? <PairView flag={flag} /> : <StepChart flag={flag} />}
      </Box>
      {transitions !== undefined && span !== undefined && (
        <Typography variant="body2" color="text.secondary">
          Switched {transitions} times within {Math.round(span)} days.
        </Typography>
      )}
    </Stack>
  );
}
