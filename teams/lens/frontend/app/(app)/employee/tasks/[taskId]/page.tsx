'use client';

import { use, useState } from 'react';
import Link from 'next/link';
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
import PersonOutlineIcon from '@mui/icons-material/PersonOutlined';
import WarningAmberOutlinedIcon from '@mui/icons-material/WarningAmberOutlined';
import { radiusTokens } from '@/theme';
import {
  DetailRow,
  displayValue,
  PriorityChip,
  softToneSx,
  statusTone,
  urgencyLabel,
} from '../../shared';
import { getErrorMessage } from '@/services/api';
import { useTaskActivity } from '@/hooks/useTaskActivity';
import { useTaskDetails } from '@/hooks/useTaskDetails';
import TaskActivity from './TaskActivity';
import TaskConstraints from './TaskConstraints';

type TabValue = 'overview' | 'activity';

export default function TaskDetailsPage({ params }: { params: Promise<{ taskId: string }> }) {
  // Next.js 16 passes params as a Promise; use() unwraps it
  const { taskId } = use(params);
  const { data: task, isPending, isError, error } = useTaskDetails(taskId);
  const [tab, setTab] = useState<TabValue>('overview');
  // Fetched only once the Activity tab is opened, then cached for this task.
  const activity = useTaskActivity(taskId, tab === 'activity');

  if (isPending) {
    return (
      <Container maxWidth="lg" sx={{ py: 4, display: 'flex', justifyContent: 'center' }}>
        <CircularProgress />
      </Container>
    );
  }

  if (isError) {
    return (
      <Container maxWidth="lg" sx={{ py: 4 }}>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
          <WarningAmberOutlinedIcon color="error" />
          <Typography color="error.main">{getErrorMessage(error)}</Typography>
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
          {task.project && (
            <Typography variant="body1" color="text.secondary">
              {task.project}
            </Typography>
          )}
        </Box>
      </Stack>

      <Tabs
        value={tab}
        onChange={(_, value: TabValue) => setTab(value)}
        aria-label="Task sections"
        sx={{ mt: 2, mb: 3 }}
      >
        <Tab
          value="overview"
          label="Overview"
          id="task-tab-overview"
          aria-controls="task-panel-overview"
        />
        <Tab
          value="dependencies"
          label={`Dependencies (${(task.dependencies ?? []).length})`}
          disabled
        />
        <Tab
          value="activity"
          label="Activity"
          id="task-tab-activity"
          aria-controls="task-panel-activity"
        />
      </Tabs>

      {tab === 'activity' && (
        <Card role="tabpanel" id="task-panel-activity" aria-labelledby="task-tab-activity">
          <CardContent>
            <TaskActivity
              activity={activity.data}
              loading={activity.isPending}
              error={activity.isError ? getErrorMessage(activity.error) : null}
              onRetry={() => activity.refetch()}
            />
          </CardContent>
        </Card>
      )}

      {tab === 'overview' && (
        <Stack
          role="tabpanel"
          id="task-panel-overview"
          aria-labelledby="task-tab-overview"
          direction={{ xs: 'column', lg: 'row' }}
          spacing={3}
          sx={{ alignItems: 'flex-start' }}
        >
          <Card sx={{ flex: 1, minWidth: 0, width: '100%' }}>
            <CardContent>
              <>
                <Typography variant="subtitle2" color="text.secondary" component="h2">
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
                      task.owner ? (
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
                      ) : (
                        '—'
                      )
                    }
                  />
                  {task.dueDate && <DetailRow label="Due" value={task.dueDate} />}
                  <DetailRow label="Priority" value={<PriorityChip priority={task.priority} />} />
                  <DetailRow
                    label="Estimated effort"
                    value={task.effortEstimate != null ? `${task.effortEstimate} min` : '—'}
                  />
                  <DetailRow label="Project" value={task.project ?? '—'} />
                  <DetailRow label="Tier" value={displayValue(task.tier)} />
                  <DetailRow label="Urgency" value={urgencyLabel(task.urgency)} />
                  <DetailRow label="Source" value={displayValue(task.sourceType)} />
                  <DetailRow label="Created" value={task.createdAt ?? '—'} />
                  <DetailRow label="Last updated" value={task.updatedAt ?? '—'} />
                  {task.snoozedUntil && (
                    <DetailRow label="Snoozed until" value={task.snoozedUntil} />
                  )}
                </Box>

                <TaskConstraints constraints={task.constraints} />
              </>
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
      )}
    </Container>
  );
}
