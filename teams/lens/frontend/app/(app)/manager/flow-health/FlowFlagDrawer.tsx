'use client';

import CloseIcon from '@mui/icons-material/Close';
import Divider from '@mui/material/Divider';
import Drawer from '@mui/material/Drawer';
import IconButton from '@mui/material/IconButton';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import StatusBadge from '@/components/ui/StatusBadge';
import type { FlowFlag } from '@/services/flowHealthApi';
import ChronicBlockerDetail from './ChronicBlockerDetail';
import RecurringCycleDetail from './RecurringCycleDetail';
import SlowChainDetail from './SlowChainDetail';
import { entityLabel, PATTERN_LABEL, SEVERITY_LABEL, SEVERITY_TONE } from './flowHealthModel';

type FlowFlagDrawerProps = {
  /** The flag to show; null closes the drawer. */
  flag: FlowFlag | null;
  onClose: () => void;
};

function PatternDetail({ flag }: { flag: FlowFlag }) {
  switch (flag.pattern) {
    case 'CHRONIC_BLOCKER':
      return <ChronicBlockerDetail flag={flag} />;
    case 'RECURRING_CYCLE':
      return <RecurringCycleDetail flag={flag} />;
    case 'SLOW_CHAIN':
      return <SlowChainDetail flag={flag} />;
  }
}

/** Details for one flag: why it was raised, and a view that fits its pattern. Read-only. */
export default function FlowFlagDrawer({ flag, onClose }: FlowFlagDrawerProps) {
  return (
    <Drawer
      anchor="right"
      open={flag !== null}
      onClose={onClose}
      slotProps={{ paper: { 'aria-labelledby': 'flow-flag-title', sx: { width: { xs: '100%', sm: 440 } } } }}
    >
      {flag && (
        <Stack spacing={2.5} sx={{ p: 3 }}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'flex-start', justifyContent: 'space-between' }}>
            <Typography id="flow-flag-title" variant="h6" component="h2" sx={{ overflowWrap: 'anywhere' }}>
              {PATTERN_LABEL[flag.pattern]}: {entityLabel(flag)}
            </Typography>
            <IconButton aria-label="Close" onClick={onClose} edge="end">
              <CloseIcon />
            </IconButton>
          </Stack>
          <Stack direction="row" spacing={1}>
            <StatusBadge status={SEVERITY_TONE[flag.severity]} label={`${SEVERITY_LABEL[flag.severity]} severity`} />
          </Stack>
          <Typography variant="body2">{flag.explanation}</Typography>
          <Divider />
          <PatternDetail flag={flag} />
          <Divider />
          <Typography variant="caption" color="text.secondary">
            דפוסים לאורך זמן, המלצה בלבד
          </Typography>
        </Stack>
      )}
    </Drawer>
  );
}
