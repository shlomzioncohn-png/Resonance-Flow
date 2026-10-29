'use client';

import { useState } from 'react';
import Link from 'next/link';
import Alert from '@mui/material/Alert';
import Avatar from '@mui/material/Avatar';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import MenuItem from '@mui/material/MenuItem';
import Snackbar from '@mui/material/Snackbar';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { alpha } from '@mui/material/styles';
import AutoAwesomeOutlinedIcon from '@mui/icons-material/AutoAwesomeOutlined';
import CheckCircleOutlinedIcon from '@mui/icons-material/CheckCircleOutlined';
import CodeOutlinedIcon from '@mui/icons-material/CodeOutlined';
import LinkOutlinedIcon from '@mui/icons-material/LinkOutlined';
import NotificationsActiveOutlinedIcon from '@mui/icons-material/NotificationsActiveOutlined';
import PersonOutlineIcon from '@mui/icons-material/PersonOutlined';
import ScheduleOutlinedIcon from '@mui/icons-material/ScheduleOutlined';
import WarningAmberOutlinedIcon from '@mui/icons-material/WarningAmberOutlined';
import PageContainer from '@/components/layout/PageContainer';
import TaskActions, { REJECT_CATEGORY_LABELS } from '@/components/ui/TaskActions';
import { useMyProjects } from '@/hooks/useMyProjects';
import { radiusTokens } from '@/theme';
import {
  DetailRow,
  iconBadgeSx,
  PriorityChip,
  priorityTone,
  softToneSx,
  statusTone,
} from './shared';
import TaskConstraints from './tasks/[taskId]/TaskConstraints';
import { ApiError, getErrorMessage } from '@/services/api';
import {
  INVALID_TASK_STATUS_TRANSITION,
  recommendTask,
  updateTaskStatus,
  type TaskRecommendation,
} from '@/services/workspaceApi';

const TIME_OPTIONS_MIN = [15, 30, 45, 60, 90];

/** Tomorrow at 09:00, the user's local time - when a snoozed task comes back. */
function tomorrowMorning(): Date {
  const date = new Date();
  date.setDate(date.getDate() + 1);
  date.setHours(9, 0, 0, 0);
  return date;
}

export default function EmployeeWorkspacePage() {
  const [minutes, setMinutes] = useState(45);
  const [projectFilter, setProjectFilter] = useState<string>('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [validationError, setValidationError] = useState<string | null>(null);
  const [recommendation, setRecommendation] = useState<TaskRecommendation | null>(null);
  // The minutes the shown recommendation was asked for - `minutes` can change after it arrives
  const [searchedMinutes, setSearchedMinutes] = useState(minutes);
  const [starting, setStarting] = useState(false);
  // Design only: Reject isn't wired yet
  const [preview, setPreview] = useState<string | null>(null);
  // Short confirmation after a status change, e.g. "Snoozed until tomorrow 09:00"
  const [notice, setNotice] = useState<string | null>(null);
  // The Project filter lists the projects of the employee's own tasks
  const myProjects = useMyProjects();

  const findBestTask = async () => {
    // Validate input
    if (!minutes || minutes < 1) {
      setValidationError('Available time must be at least 1 minute');
      return;
    }

    setError(null);
    setValidationError(null);
    setLoading(true);
    try {
      const result = await recommendTask({
        availableTime: minutes,
        projectFilter: projectFilter || undefined,
      });
      setRecommendation(result);
      setSearchedMinutes(minutes);
    } catch (err) {
      setError(getErrorMessage(err));
      setRecommendation(null);
    } finally {
      setLoading(false);
    }
  };

  // Start task -> IN_PROGRESS, Mark as done -> DONE, Can't do it now -> SNOOZED
  const changeStatus = async (status: 'IN_PROGRESS' | 'DONE' | 'SNOOZED', snoozedUntil?: Date) => {
    if (!recommendation?.task) return;
    setError(null); // an older error must not stay on screen after a successful change
    setStarting(true);
    try {
      const updated = await updateTaskStatus(recommendation.task.id, status, snoozedUntil);
      if (status === 'DONE') setNotice('Task marked as done.');
      if (status === 'SNOOZED') setNotice('Snoozed until tomorrow 09:00.');
      // Reflect the new status in the displayed recommendation
      setRecommendation((prev) => (prev ? { ...prev, task: updated } : prev));
    } catch (err) {
      if (err instanceof ApiError && err.code === INVALID_TASK_STATUS_TRANSITION) {
        setError('This task is already done, so its status can no longer be changed.');
      } else {
        setError(getErrorMessage(err));
      }
    } finally {
      setStarting(false);
    }
  };

  const task = recommendation?.task ?? null;
  // The server decides (it also knows when the effort is unknown) - not recomputed here
  const fitsWindow = recommendation?.fitsInTime ?? false;

  return (
    <PageContainer title="My Workspace" description="Make the most of your available time.">
      <Stack spacing={3}>
        <Card
          sx={{
            border: 'none',
            bgcolor: (theme) => alpha(theme.palette.primary.main, 0.05),
          }}
        >
          <CardContent>
            <Stack
              direction={{ xs: 'column', sm: 'row' }}
              spacing={3}
              sx={{ alignItems: { sm: 'center' } }}
            >
              <Box sx={iconBadgeSx}>
                <ScheduleOutlinedIcon />
              </Box>

              <Box sx={{ flexGrow: 1 }}>
                <Typography variant="h6">What can I accomplish right now?</Typography>
                <Typography variant="body2" color="text.secondary">
                  Tell us how much time you have and we will find the best task to do.
                </Typography>
              </Box>

              <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexShrink: 0 }}>
                <TextField
                  select
                  size="small"
                  label="Project"
                  value={projectFilter}
                  onChange={(event) => setProjectFilter(event.target.value)}
                  sx={{ width: 150, bgcolor: 'background.paper', borderRadius: radiusTokens.sm }}
                  data-testid="project-filter"
                >
                  <MenuItem value="">All Projects</MenuItem>
                  {(myProjects.data ?? []).map((name) => (
                    <MenuItem key={name} value={name}>
                      {name}
                    </MenuItem>
                  ))}
                </TextField>
                <TextField
                  select
                  size="small"
                  label="I have"
                  value={minutes}
                  onChange={(event) => setMinutes(Number(event.target.value))}
                  sx={{ width: 120, bgcolor: 'background.paper', borderRadius: radiusTokens.sm }}
                  data-testid="available-time"
                >
                  {TIME_OPTIONS_MIN.map((option) => (
                    <MenuItem key={option} value={option}>
                      {option} min
                    </MenuItem>
                  ))}
                </TextField>
                <Button
                  variant="contained"
                  startIcon={
                    loading ? (
                      <CircularProgress size={16} color="inherit" />
                    ) : (
                      <AutoAwesomeOutlinedIcon />
                    )
                  }
                  onClick={findBestTask}
                  // Not while a task is starting - its answer would land on the new recommendation
                  disabled={loading || starting}
                  data-testid="recommend-btn"
                >
                  {loading ? 'Finding…' : 'Find my best task'}
                </Button>
              </Stack>
            </Stack>
          </CardContent>
        </Card>

        {/* Validation error */}
        {validationError && (
          <Card
            sx={{ border: '1px solid', borderColor: 'warning.main' }}
            data-testid="validation-error"
          >
            <CardContent>
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                <WarningAmberOutlinedIcon color="warning" />
                <Typography color="warning.main" variant="body2">
                  {validationError}
                </Typography>
              </Stack>
            </CardContent>
          </Card>
        )}

        {/* Error state */}
        {error && (
          <Card sx={{ border: '1px solid', borderColor: 'error.main' }} data-testid="error-message">
            <CardContent>
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                <WarningAmberOutlinedIcon color="error" />
                <Typography color="error.main" variant="body2">
                  {error}
                </Typography>
              </Stack>
            </CardContent>
          </Card>
        )}

        {/* No task found - show the server's reason instead of an empty screen */}
        {recommendation && !task && (
          <Card data-testid="no-recommendation">
            <CardContent>
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                <ScheduleOutlinedIcon color="action" />
                <Typography variant="body2" color="text.secondary">
                  {recommendation.explanation || 'No matching tasks available right now.'}
                </Typography>
              </Stack>
            </CardContent>
          </Card>
        )}

        {/* Recommendation result */}
        {recommendation && task && (
          <Stack direction={{ xs: 'column', lg: 'row' }} spacing={3} sx={{ alignItems: 'stretch' }}>
            <Card sx={{ flex: 1, minWidth: 0 }}>
              <CardContent>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 2 }}>
                  <AutoAwesomeOutlinedIcon fontSize="small" color="primary" />
                  <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
                    Best task for you
                  </Typography>
                  {recommendation.confidenceScore > 0 && (
                    <Chip
                      size="small"
                      label={`${recommendation.confidenceScore}% match`}
                      sx={softToneSx('success')}
                      data-testid="confidence-score"
                    />
                  )}
                </Stack>

                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                  <Box sx={iconBadgeSx}>
                    <CodeOutlinedIcon />
                  </Box>

                  <Box sx={{ flexGrow: 1 }}>
                    <Stack
                      direction="row"
                      spacing={2}
                      sx={{ alignItems: 'center', justifyContent: 'space-between' }}
                    >
                      <Typography variant="h6" data-testid="recommendation-task-title">
                        {task.title}
                      </Typography>
                      <PriorityChip priority={task.priority} bold />
                    </Stack>
                    {task.project && (
                      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                        {task.project}
                      </Typography>
                    )}

                    <Stack spacing={0.75} sx={{ mb: 3 }}>
                      <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                        {fitsWindow ? (
                          <CheckCircleOutlinedIcon fontSize="small" color="success" />
                        ) : (
                          <ScheduleOutlinedIcon fontSize="small" color="warning" />
                        )}
                        <Typography variant="body2" data-testid="recommendation-explanation">
                          {recommendation.explanation}
                        </Typography>
                      </Stack>
                      {recommendation.isHighPriority && (
                        <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                          <CheckCircleOutlinedIcon fontSize="small" color="success" />
                          <Typography variant="body2">High importance</Typography>
                        </Stack>
                      )}
                      {recommendation.hasNoDependencies && (
                        <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                          <CheckCircleOutlinedIcon fontSize="small" color="success" />
                          <Typography variant="body2">No blocking dependencies</Typography>
                        </Stack>
                      )}
                    </Stack>

                    <TaskActions
                      status={task.status}
                      detailsHref={`/employee/tasks/${task.id}`}
                      linkComponent={Link}
                      busy={starting || loading}
                      onStart={() => changeStatus('IN_PROGRESS')}
                      onDone={() => changeStatus('DONE')}
                      // Inbox has no "blocked" status or reason field - the task is
                      // snoozed until tomorrow morning, and the reason isn't saved yet
                      onBlock={() => changeStatus('SNOOZED', tomorrowMorning())}
                      onReject={(details) =>
                        setPreview(`Reject task: ${REJECT_CATEGORY_LABELS[details.category]}`)
                      }
                    />
                  </Box>
                </Stack>
              </CardContent>
            </Card>

            <Card sx={{ width: { xs: '100%', lg: 360 }, flexShrink: 0 }}>
              <CardContent>
                <Typography variant="subtitle2" sx={{ mb: 2 }}>
                  Task details
                </Typography>

                <Stack
                  direction="row"
                  spacing={2}
                  sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 0.5 }}
                >
                  <Typography variant="h6">{task.title}</Typography>
                  <Chip
                    size="small"
                    label={task.status}
                    sx={[softToneSx(statusTone(task.status)), { fontWeight: 600 }]}
                  />
                </Stack>
                {task.project && (
                  <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                    {task.project}
                  </Typography>
                )}

                <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', mb: 3 }}>
                  <Chip
                    size="small"
                    icon={<NotificationsActiveOutlinedIcon />}
                    label={task.priority ?? '—'}
                    sx={softToneSx(priorityTone(task.priority))}
                  />
                  <Chip
                    size="small"
                    icon={<ScheduleOutlinedIcon />}
                    label={
                      fitsWindow
                        ? `Fits in ${searchedMinutes} min`
                        : task.effortEstimate != null
                          ? `Needs ${task.effortEstimate} min`
                          : 'Effort unknown'
                    }
                    sx={softToneSx(fitsWindow ? 'info' : 'warning')}
                  />
                  {(task.dependencies ?? []).length > 0 && (
                    <Chip
                      size="small"
                      icon={<LinkOutlinedIcon />}
                      label={`${(task.dependencies ?? []).length} dependenc${(task.dependencies ?? []).length === 1 ? 'y' : 'ies'}`}
                      sx={softToneSx('secondary')}
                    />
                  )}
                </Stack>

                <Stack spacing={2}>
                  <DetailRow label="Description" value={task.description} />
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
                          <span data-testid="task-owner">{task.owner}</span>
                        </Stack>
                      ) : (
                        '—'
                      )
                    }
                  />
                  {task.dueDate && <DetailRow label="Due" value={task.dueDate} />}
                  <DetailRow
                    label="Estimated effort"
                    value={task.effortEstimate != null ? `${task.effortEstimate} min` : '—'}
                  />
                  <DetailRow label="Project" value={task.project ?? '—'} />
                  <TaskConstraints constraints={task.constraints} />
                </Stack>
              </CardContent>
            </Card>
          </Stack>
        )}
      </Stack>
      <Snackbar
        open={preview !== null}
        autoHideDuration={4000}
        onClose={() => setPreview(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        <Alert severity="info" variant="filled" onClose={() => setPreview(null)}>
          Preview only - not saved yet. {preview}
        </Alert>
      </Snackbar>
      <Snackbar
        open={notice !== null}
        autoHideDuration={4000}
        onClose={() => setNotice(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        <Alert severity="success" variant="filled" onClose={() => setNotice(null)}>
          {notice}
        </Alert>
      </Snackbar>
    </PageContainer>
  );
}
