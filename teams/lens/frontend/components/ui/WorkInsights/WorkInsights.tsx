'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import Fab from '@mui/material/Fab';
import AutoAwesomeIcon from '@mui/icons-material/AutoAwesome';
import { getErrorMessage } from '@/services/api';
import { getWorkInsight, sendInsightFeedback, type WorkInsight } from '@/services/workInsightsApi';
import WorkInsightsDrawer, { type FeedbackState, type InsightLoadState } from './WorkInsightsDrawer';

const AUTO_CLOSE_MS = 4000;

export default function WorkInsights() {
  const [open, setOpen] = useState(false);
  const [state, setState] = useState<InsightLoadState>('loading');
  const [insight, setInsight] = useState<WorkInsight | null>(null);
  const [errorMessage, setErrorMessage] = useState('');
  const [feedback, setFeedback] = useState<FeedbackState>('idle');
  // An older request that answers late must not overwrite a newer one
  const latestRequest = useRef(0);
  const lastHelpful = useRef<boolean | null>(null);

  const loadInsight = useCallback(() => {
    const requestId = ++latestRequest.current;
    setState('loading');
    setInsight(null);
    setFeedback('idle');
    getWorkInsight()
      .then((response) => {
        if (requestId !== latestRequest.current) return;
        setInsight(response);
        setState('loaded');
      })
      .catch((err) => {
        if (requestId !== latestRequest.current) return;
        setErrorMessage(getErrorMessage(err));
        setState('error');
      });
  }, []);

  const nothingToShow =
    state === 'loaded' && (insight?.status === 'NO_INSIGHT' || insight?.status === 'NOT_ENOUGH_DATA');

  useEffect(() => {
    if (!open || !nothingToShow) return;
    const timer = setTimeout(() => setOpen(false), AUTO_CLOSE_MS);
    return () => clearTimeout(timer);
  }, [open, nothingToShow]);

  const handleOpen = () => {
    setOpen(true);
    loadInsight();
  };

  // A failed send keeps the insight on screen and only offers to send the same answer again
  const handleFeedback = (helpful: boolean) => {
    if (!insight?.insightId) return;
    const requestId = latestRequest.current;
    lastHelpful.current = helpful;
    setFeedback('sending');
    sendInsightFeedback(insight.insightId, { helpful })
      .then(() => {
        if (requestId === latestRequest.current) setFeedback('sent');
      })
      .catch(() => {
        if (requestId === latestRequest.current) setFeedback('failed');
      });
  };

  const retryFeedback = () => {
    if (lastHelpful.current !== null) handleFeedback(lastHelpful.current);
  };

  return (
    <>
      <Fab
        variant="extended"
        onClick={handleOpen}
        aria-haspopup="dialog"
        sx={{
          position: 'fixed',
          bottom: (theme) => theme.spacing(3),
          right: (theme) => theme.spacing(3),
          gap: 1,
          color: 'common.white',
          textTransform: 'none',
          fontWeight: 700,
          background: (theme) => theme.custom.ai.gradient,
          boxShadow: (theme) => theme.custom.ai.glow,
          '&:hover': {
            background: (theme) => theme.custom.ai.gradient,
            transform: 'translateY(-2px)',
          },
        }}
      >
        <AutoAwesomeIcon fontSize="small" />
        Personal insight
      </Fab>

      <WorkInsightsDrawer
        open={open}
        onClose={() => setOpen(false)}
        state={state}
        insight={insight}
        errorMessage={errorMessage}
        onRetry={loadInsight}
        feedback={feedback}
        onFeedback={handleFeedback}
        onRetryFeedback={retryFeedback}
      />
    </>
  );
}
