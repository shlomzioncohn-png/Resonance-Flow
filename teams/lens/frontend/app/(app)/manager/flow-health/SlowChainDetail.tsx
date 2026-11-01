'use client';

import Box from '@mui/material/Box';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import type { FlowFlag } from '@/services/flowHealthApi';
import { chainLinks, evidenceNumber } from './flowHealthModel';

/**
 * Slow chain: one horizontal bar per link, in chain order. The bars need per-link
 * durations (`link_days` in the evidence); without them the links are listed with
 * the chain's totals only, rather than drawing made-up lengths.
 */
export default function SlowChainDetail({ flag }: { flag: FlowFlag }) {
  const theme = useTheme();
  const links = chainLinks(flag);
  const hasDurations = links.every((link) => link.days !== null);
  const longest = Math.max(...links.map((link) => link.days ?? 0), 1);
  const total = evidenceNumber(flag.evidence, 'end_to_end_days');

  return (
    <Stack spacing={2}>
      <Box component="section" aria-label="Chain links">
        <Typography variant="subtitle2" component="h3" sx={{ mb: 1 }}>
          Links ({links.length})
        </Typography>
        <Stack component="ol" spacing={1.25} sx={{ listStyle: 'none', p: 0, m: 0 }}>
          {links.map((link) => (
            <li key={link.taskId}>
              <Typography variant="body2" sx={{ overflowWrap: 'anywhere' }}>
                {link.title}
                {link.days !== null && ` · ${Math.round(link.days)} days`}
              </Typography>
              {hasDurations && link.days !== null && (
                <Box sx={{ height: 12, bgcolor: 'action.hover', borderRadius: 1 }}>
                  <Box
                    role="presentation"
                    sx={{
                      width: `${(link.days / longest) * 100}%`,
                      height: '100%',
                      borderRadius: 1,
                      bgcolor:
                        link.days === longest ? theme.palette.status.critical.main : theme.palette.status.warning.main,
                    }}
                  />
                </Box>
              )}
            </li>
          ))}
        </Stack>
      </Box>
      {!hasDurations && (
        <Typography variant="body2" color="text.secondary">
          This run has no per-link durations, so only the chain&apos;s total is shown.
        </Typography>
      )}
      {total !== undefined && (
        <Typography variant="body2" color="text.secondary">
          End to end: {Math.round(total)} days.
        </Typography>
      )}
    </Stack>
  );
}
