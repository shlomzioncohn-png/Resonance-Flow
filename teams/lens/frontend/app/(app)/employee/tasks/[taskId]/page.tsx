'use client';

import { use, useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import Avatar from '@mui/material/Avatar';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import Container from '@mui/material/Container';
import Stack from '@mui/material/Stack';
import Tab from '@mui/material/Tab';
import Tabs from '@mui/material/Tabs';
import Typography from '@mui/material/Typography';
import { alpha } from '@mui/material/styles';
import ArrowBackOutlinedIcon from '@mui/icons-material/ArrowBackOutlined';
import ChatBubbleOutlineOutlinedIcon from '@mui/icons-material/ChatBubbleOutlineOutlined';
import PersonOutlineIcon from '@mui/icons-material/PersonOutlined';
import WarningAmberOutlinedIcon from '@mui/icons-material/WarningAmberOutlined';
import { radiusTokens } from '@/theme';
import { DetailRow, softToneSx, statusTone } from '../../shared';
import { getTask, type ApiTask } from '@/services/workspaceApi';
import ChatPanel from './ChatPanel';

export default function TaskDetailsPage({ params }: { params: Promise<{ taskId: string }> }) {
  const { taskId } = use(params);
  const searchParams = useSearchParams();
  const [mode, setMode] = useState<'overview' | 'chat'>(
    searchParams.get('chat') === '1' ? 'chat' : 'overview',
  );
  const [task, setTask] = useState<ApiTask | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // Resets loading synchronously so navigating between two task pages
    // (taskId changes without the component remounting) shows the spinner
    // instead of a stale flash of the previous task's content/error while
    // the new fetch is in flight - the default useState(true) only covers
    // the very first render, not a later taskId change.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    let ignore = false;
    getTask(taskId)
      .then((result) => {
        if (!ignore) setTask(result);
      })
      .catch((err) => {
        if (!ignore) setError(err instanceof Error ? err.message : 'Failed to load task');
      })
      .finally(() => {
        if (!ignore) setLoading(false);
      });
    return () => {
      ignore = true;
    };
  }, [taskId]);

  const contentRef = useRef<HTMLDivElement>(null);
  const isFirstRender = useRef(true);
  useEffect(() => {
    if (isFirstRender.current) {
      isFirstRender.current = false;
      return;
    }
    contentRef.current?.focus();
  }, [mode]);

  if (loading) {
    return (
      <Container maxWidth="lg" sx={{ py: 4, display: 'flex', justifyContent: 'center' }}>
        <CircularProgress />
      </Container>
    );
  }

  if (error || !task) {
    return (
      <Container maxWidth="lg" sx={{ py: 4 }}>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
          <WarningAmberOutlinedIcon color="error" />
          <Typography color="error.main">{error ?? 'Task not found'}</Typography>
        </Stack>
      </Container>
    );
  }

  return (
    <Container maxWidth="lg" sx={{ py: { xs: 3, md: 4 } }}>
      <Button
        component={Link}
        href="/employee/tasks"
        startIcon={<ArrowBackOutlinedIcon />}
        color="inherit"
        size="small"
        sx={{ mb: 2, color: 'text.secondary' }}
      >
        Back to My Tasks
      </Button>

      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        sx={{ alignItems: { sm: 'center' }, justifyContent: 'space-between', mb: 0.5 }}
      >
        <Box>
          <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
            <Typography variant="h4" component="h1">
              {task.title}
            </Typography>
            <Chip
              size="small"
              label={task.status}
              sx={[softToneSx(statusTone(task.status)), { fontWeight: 600 }]}
            />
          </Stack>
          <Typography variant="body1" color="text.secondary">
            {task.project}
          </Typography>
        </Box>
      </Stack>

      <Tabs value={mode === 'chat' ? false : 'overview'} onChange={() => {}} sx={{ mt: 2, mb: 3 }}>
        <Tab value="overview" label="Overview" />
        <Tab
          value="dependencies"
          label={`Dependencies (${(task.dependencies ?? []).length})`}
          disabled
        />
        <Tab value="activity" label="Activity" disabled />
      </Tabs>

      <Stack direction={{ xs: 'column', lg: 'row' }} spacing={3} sx={{ alignItems: 'flex-start' }}>
        <Card sx={{ flex: 1, minWidth: 0, width: '100%' }}>
          <CardContent ref={contentRef} tabIndex={-1} sx={{ outline: 'none' }}>
            {mode === 'chat' ? (
              <ChatPanel onBack={() => setMode('overview')} />
            ) : (
              <>
                <Typography variant="subtitle2" color="text.secondary">
                  Description
                </Typography>
                <Typography variant="body1" sx={{ mb: 3 }}>
                  {task.description}
                </Typography>

                <Box
                  sx={{
                    display: 'grid',
                    gridTemplateColumns: { xs: '1fr', sm: 'repeat(3, 1fr)' },
                    gap: 3,
                    mb: 3,
                  }}
                >
                  <DetailRow
                    label="Owner"
                    value={
                      <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                        <Avatar
                          sx={{
                            width: 24,
                            height: 24,
                            bgcolor: (theme) => alpha(theme.palette.primary.main, 0.12),
                            color: 'primary.main',
                          }}
                        >
                          <PersonOutlineIcon sx={{ fontSize: 16 }} />
                        </Avatar>
                        {task.owner}
                      </Stack>
                    }
                  />
                  {task.dueDate && (
                    <DetailRow label="Due" value={new Date(task.dueDate).toLocaleString()} />
                  )}
                  <DetailRow
                    label="Priority"
                    value={<Chip size="small" label={task.priority} sx={softToneSx('error')} />}
                  />
                  <DetailRow
                    label="Estimated effort"
                    value={task.effortEstimate != null ? `${task.effortEstimate} min` : '—'}
                  />
                  <DetailRow label="Project" value={task.project} />
                  {(task.constraints ?? []).length > 0 && (
                    <DetailRow label="Constraints" value={(task.constraints ?? []).join(', ')} />
                  )}
                </Box>

                <Button
                  variant="outlined"
                  startIcon={<ChatBubbleOutlineOutlinedIcon />}
                  onClick={() => setMode('chat')}
                >
                  Chat about this task
                </Button>
              </>
            )}
          </CardContent>
        </Card>

        {(task.dependencies ?? []).length > 0 && (
          <Card sx={{ width: { xs: '100%', lg: 360 }, flexShrink: 0 }}>
            <CardContent>
              <Typography variant="subtitle2" sx={{ mb: 2 }}>
                Dependencies ({task.dependencies!.length})
              </Typography>
              <Stack spacing={1}>
                {task.dependencies!.map((depId) => (
                  <Stack
                    key={depId}
                    direction="row"
                    sx={{
                      alignItems: 'center',
                      p: 1,
                      borderRadius: radiusTokens.sm,
                      bgcolor: 'action.hover',
                    }}
                  >
                    <Typography variant="body2">{depId}</Typography>
                  </Stack>
                ))}
              </Stack>
            </CardContent>
          </Card>
        )}
      </Stack>
    </Container>
  );
}
