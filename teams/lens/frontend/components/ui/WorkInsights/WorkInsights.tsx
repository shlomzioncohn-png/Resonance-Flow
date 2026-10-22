'use client';

import { useCallback, useRef, useState } from 'react';
import Fab from '@mui/material/Fab';
import AutoAwesomeIcon from '@mui/icons-material/AutoAwesome';
import { getErrorMessage } from '@/services/api';
import { getWorkInsight, sendInsightFeedback, type WorkInsight } from '@/services/workInsightsApi';
import WorkInsightsDrawer, { type InsightLoadState } from './WorkInsightsDrawer';

export default function WorkInsights() {
  const [open, setOpen] = useState(false);
  const [state, setState] = useState<InsightLoadState>('loading');
  const [insight, setInsight] = useState<WorkInsight | null>(null);
  const [errorMessage, setErrorMessage] = useState('');
  const [feedbackSent, setFeedbackSent] = useState(false);
  // An older request that answers late must not overwrite a newer one
  const latestRequest = useRef(0);

  const loadInsight = useCallback(() => {
    const requestId = ++latestRequest.current;
    setState('loading');
    setInsight(null);
    setFeedbackSent(false);
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

  const handleOpen = () => {
    setOpen(true);
    loadInsight();
  };

  const handleFeedback = (helpful: boolean) => {
    if (!insight) return;
    setFeedbackSent(true);
    // Feedback is best-effort: a failed send must not take the insight off the screen
    sendInsightFeedback(insight.insightId, { helpful }).catch(() => {});
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
        feedbackSent={feedbackSent}
        onFeedback={handleFeedback}
      />
    </>
  );
}
