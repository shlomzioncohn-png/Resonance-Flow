'use client';

import { useEffect, useState } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Drawer from '@mui/material/Drawer';
import Fade from '@mui/material/Fade';
import IconButton from '@mui/material/IconButton';
import LinearProgress from '@mui/material/LinearProgress';
import Skeleton from '@mui/material/Skeleton';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import useMediaQuery from '@mui/material/useMediaQuery';
import { keyframes } from '@mui/material/styles';
import AutoAwesomeIcon from '@mui/icons-material/AutoAwesome';
import CloseIcon from '@mui/icons-material/Close';
import ErrorState from '@/components/feedback/ErrorState';
import type { WorkInsight } from '@/services/workInsightsApi';

export type InsightLoadState = 'loading' | 'loaded' | 'error';

export interface WorkInsightsDrawerProps {
  open: boolean;
  onClose: () => void;
  state: InsightLoadState;
  insight: WorkInsight | null;
  errorMessage: string;
  onRetry: () => void;
  feedbackSent: boolean;
  onFeedback: (helpful: boolean) => void;
}

const TYPING_DELAY_MS = 28;
const REVEAL_STEP_MS = 280;
const TITLE_ID = 'work-insights-title';
const REDUCED_MOTION = '(prefers-reduced-motion: reduce)';

const bob = keyframes`
  0%, 100% { transform: translateY(0); opacity: 0.5; }
  50% { transform: translateY(-4px); opacity: 1; }
`;

export default function WorkInsightsDrawer({
  open,
  onClose,
  state,
  insight,
  errorMessage,
  onRetry,
  feedbackSent,
  onFeedback,
}: WorkInsightsDrawerProps) {
  return (
    <Drawer
      anchor="right"
      open={open}
      onClose={onClose}
      slotProps={{
        paper: {
          'aria-labelledby': TITLE_ID,
          sx: { width: { xs: '100%', sm: 380 }, display: 'flex', flexDirection: 'column' },
        },
      }}
    >
      <Stack
        direction="row"
        spacing={1.5}
        sx={{
          alignItems: 'center',
          px: 2.5,
          py: 2,
          color: 'common.white',
          background: (theme) => theme.custom.ai.gradient,
        }}
      >
        <AutoAwesomeIcon />
        <Box sx={{ flexGrow: 1 }}>
          <Typography id={TITLE_ID} variant="subtitle1" component="h2" sx={{ fontWeight: 700 }}>
            My insights
          </Typography>
          {insight && (
            <Typography variant="caption" sx={{ opacity: 0.85 }}>
              Based on the last {insight.periodDays} days
            </Typography>
          )}
        </Box>
        <IconButton aria-label="Close insights" onClick={onClose} sx={{ color: 'common.white' }}>
          <CloseIcon />
        </IconButton>
      </Stack>

      <Box sx={{ p: 2.5, display: 'flex', flexDirection: 'column', gap: 2, flexGrow: 1 }}>
        {state === 'loading' && <LoadingView />}
        {state === 'error' && (
          <ErrorState
            title="We couldn't create your insight"
            description={errorMessage}
            onRetry={onRetry}
          />
        )}
        {state === 'loaded' && insight?.status === 'NOT_ENOUGH_DATA' && <NotEnoughDataView />}
        {state === 'loaded' && insight?.status === 'READY' && (
          <InsightView
            key={insight.insightId}
            insight={insight}
            feedbackSent={feedbackSent}
            onFeedback={onFeedback}
          />
        )}
      </Box>
    </Drawer>
  );
}

function LoadingView() {
  return (
    <Box role="status">
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', color: 'primary.main' }}>
        {[0, 1, 2].map((dot) => (
          <Box
            key={dot}
            sx={{
              width: 7,
              height: 7,
              borderRadius: '50%',
              bgcolor: (theme) => theme.custom.ai.accent,
              animation: `${bob} 1s infinite`,
              animationDelay: `${dot * 0.15}s`,
              [`@media ${REDUCED_MOTION}`]: { animation: 'none' },
            }}
          />
        ))}
        <Typography variant="body2" sx={{ fontWeight: 600, pl: 0.5 }}>
          Analyzing your tasks…
        </Typography>
      </Stack>
      <Stack spacing={1} sx={{ mt: 2 }}>
        <Skeleton variant="rounded" height={12} />
        <Skeleton variant="rounded" height={12} width="80%" />
        <Skeleton variant="rounded" height={12} width="60%" />
      </Stack>
    </Box>
  );
}

function NotEnoughDataView() {
  return (
    <Box sx={{ textAlign: 'center', py: 6 }}>
      <Typography variant="h6" component="h3">
        Not enough data yet
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
        Once you complete a few more tasks, we&apos;ll have an insight for you.
      </Typography>
    </Box>
  );
}

interface InsightViewProps {
  insight: WorkInsight;
  feedbackSent: boolean;
  onFeedback: (helpful: boolean) => void;
}

function InsightView({ insight, feedbackSent, onFeedback }: InsightViewProps) {
  const text = insight.insight ?? '';
  const reduceMotion = useMediaQuery(REDUCED_MOTION);
  const [typedLength, setTypedLength] = useState(0);
  const visibleLength = reduceMotion ? text.length : typedLength;
  const typingDone = visibleLength >= text.length;

  useEffect(() => {
    if (reduceMotion || typedLength >= text.length) return;
    const timer = setTimeout(() => setTypedLength(typedLength + 1), TYPING_DELAY_MS);
    return () => clearTimeout(timer);
  }, [reduceMotion, typedLength, text.length]);

  const reveal = (step: number) => ({
    in: typingDone,
    timeout: reduceMotion ? 0 : 400,
    style: { transitionDelay: reduceMotion ? '0ms' : `${step * REVEAL_STEP_MS}ms` },
  });

  const evidence = insight.evidence;

  return (
    <>
      <Typography variant="h6" component="p" aria-live="polite" sx={{ fontWeight: 700, minHeight: 64 }}>
        {text.slice(0, visibleLength)}
      </Typography>

      {insight.recommendation && (
        <Fade {...reveal(0)}>
          <Box sx={{ bgcolor: (theme) => theme.custom.ai.surface, borderRadius: 1, px: 2, py: 1.5 }}>
            <Typography variant="body2">
              <Box component="strong" sx={{ color: 'primary.main' }}>
                Maybe try:
              </Box>{' '}
              {insight.recommendation}
            </Typography>
          </Box>
        </Fade>
      )}

      {evidence && (
        <Fade {...reveal(1)}>
          <Box sx={{ border: 1, borderColor: 'divider', borderRadius: 1, px: 2, py: 1.5 }}>
            <Typography variant="subtitle2">What this is based on</Typography>
            <Typography variant="body2" color="text.secondary">
              {evidence.text}
            </Typography>
            <LinearProgress
              variant="determinate"
              value={evidence.total > 0 ? (evidence.achieved / evidence.total) * 100 : 0}
              aria-label={`${evidence.achieved} of ${evidence.total}`}
              sx={{
                mt: 1,
                height: 8,
                borderRadius: 1,
                '& .MuiLinearProgress-bar': { bgcolor: (theme) => theme.custom.ai.accent },
              }}
            />
          </Box>
        </Fade>
      )}

      <Fade {...reveal(2)}>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mt: 'auto' }}>
          {feedbackSent ? (
            <Typography variant="body2" color="success.main" sx={{ fontWeight: 600 }}>
              Thanks, we&apos;ll learn from this
            </Typography>
          ) : (
            <>
              <Typography variant="body2" color="text.secondary">
                Was this helpful?
              </Typography>
              <Button size="small" variant="contained" onClick={() => onFeedback(true)}>
                Helpful
              </Button>
              <Button size="small" variant="outlined" onClick={() => onFeedback(false)}>
                Not relevant
              </Button>
            </>
          )}
        </Stack>
      </Fade>
    </>
  );
}
