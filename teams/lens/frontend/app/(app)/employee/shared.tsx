import type { ReactNode } from 'react';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import Typography from '@mui/material/Typography';
import { alpha, type Theme } from '@mui/material/styles';
import { radiusTokens } from '@/theme';

export type Tone = 'error' | 'info' | 'primary' | 'secondary' | 'success' | 'warning';

// Soft "pastel" chip look (light tinted background, matching-color text/icon) built from
// the theme's own palette entries via alpha() — MUI's Chip has no built-in "soft" variant.
export function softToneSx(tone: Tone) {
  return (theme: Theme) => ({
    bgcolor: alpha(theme.palette[tone].main, 0.12),
    color: theme.palette[tone].dark,
    '& .MuiChip-icon': { color: theme.palette[tone].main },
  });
}

export function iconBadgeSx(theme: Theme) {
  return {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    width: 48,
    height: 48,
    flexShrink: 0,
    borderRadius: radiusTokens.md,
    bgcolor: alpha(theme.palette.primary.main, 0.1),
    color: 'primary.main',
  };
}

/**
 * Maps one of Inbox's 8 task statuses to a display tone, grouped by meaning:
 * not started yet (NEW, IN_REVIEW) / ready (APPROVED) / active (IN_PROGRESS) /
 * paused (SNOOZED) / done (DONE) / closed without being done (REJECTED, CANCELLED).
 * READY/BLOCKED are only the My Workspace employee-task demo's old values.
 */
export function statusTone(status: string): Tone {
  switch (status.toUpperCase()) {
    case 'APPROVED':
    case 'READY':
      return 'primary';
    case 'IN_PROGRESS':
      return 'info';
    case 'SNOOZED':
    case 'BLOCKED':
      return 'warning';
    case 'DONE':
      return 'success';
    case 'REJECTED':
    case 'CANCELLED':
      return 'error';
    default: // NEW, IN_REVIEW, unknown
      return 'secondary';
  }
}

/** Maps a task priority (HIGH/MEDIUM/LOW, any casing) to a display tone. No priority -> neutral. */
export function priorityTone(priority?: string): Tone {
  if (!priority) return 'secondary';
  const normalised = priority.toUpperCase();
  if (normalised === 'HIGH') return 'error';
  if (normalised === 'MEDIUM') return 'warning';
  return 'success'; // LOW or unknown
}

/**
 * Priority chip, colored by its value. A task with no priority score has no
 * priority at all - shows a dash rather than pretending it's MEDIUM.
 */
export function PriorityChip({ priority, bold = false }: { priority?: string; bold?: boolean }) {
  if (!priority) return <>—</>;
  return (
    <Chip
      size="small"
      label={priority}
      sx={[softToneSx(priorityTone(priority)), bold && { fontWeight: 600 }]}
      data-testid="task-priority"
    />
  );
}

export function DetailRow({ label, value }: { label: string; value: ReactNode }) {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary" component="div">
        {label}
      </Typography>
      <Box sx={{ typography: 'body2', mt: 0.25 }}>{value}</Box>
    </Box>
  );
}
