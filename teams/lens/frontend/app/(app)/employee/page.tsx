'use client'

import { useState } from 'react'
import Link from 'next/link'
import Avatar from '@mui/material/Avatar'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Chip from '@mui/material/Chip'
import CircularProgress from '@mui/material/CircularProgress'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import ArrowForwardOutlinedIcon from '@mui/icons-material/ArrowForwardOutlined'
import AutoAwesomeOutlinedIcon from '@mui/icons-material/AutoAwesomeOutlined'
import CheckCircleOutlinedIcon from '@mui/icons-material/CheckCircleOutlined'
import ChatBubbleOutlineOutlinedIcon from '@mui/icons-material/ChatBubbleOutlineOutlined'
import CodeOutlinedIcon from '@mui/icons-material/CodeOutlined'
import LinkOutlinedIcon from '@mui/icons-material/LinkOutlined'
import NotificationsActiveOutlinedIcon from '@mui/icons-material/NotificationsActiveOutlined'
import PersonOutlineIcon from '@mui/icons-material/PersonOutlined'
import PlayArrowOutlinedIcon from '@mui/icons-material/PlayArrowOutlined'
import ScheduleOutlinedIcon from '@mui/icons-material/ScheduleOutlined'
import WarningAmberOutlinedIcon from '@mui/icons-material/WarningAmberOutlined'
import PageContainer from '@/components/layout/PageContainer'
import { radiusTokens } from '@/theme'
import { DetailRow, iconBadgeSx, softToneSx, statusTone } from './shared'
import { recommendTask, updateTaskStatus, type TaskRecommendation } from '@/services/workspaceApi'

const TIME_OPTIONS_MIN = [15, 30, 45, 60, 90]

export default function EmployeeWorkspacePage() {
  const [minutes, setMinutes] = useState(45)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [recommendation, setRecommendation] = useState<TaskRecommendation | null>(null)
  const [starting, setStarting] = useState(false)
  const [started, setStarted] = useState(false)

  const findBestTask = async () => {
    setStarted(false)
    setError(null)
    setLoading(true)
    try {
      const result = await recommendTask({ availableTime: minutes })
      setRecommendation(result)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to fetch recommendation')
      setRecommendation(null)
    } finally {
      setLoading(false)
    }
  }

  const startTask = async () => {
    if (!recommendation?.task) return
    setStarting(true)
    try {
      const updated = await updateTaskStatus(recommendation.task.id, 'IN_PROGRESS')
      // Reflect the new status in the displayed recommendation
      setRecommendation((prev) => prev ? { ...prev, task: updated } : prev)
      setStarted(true)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to start task')
    } finally {
      setStarting(false)
    }
  }

  const task = recommendation?.task ?? null
  const fitsWindow = task ? minutes >= task.effortEstimate : false

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
                  label="I have"
                  value={minutes}
                  onChange={(event) => setMinutes(Number(event.target.value))}
                  sx={{ width: 120, bgcolor: 'background.paper', borderRadius: radiusTokens.sm }}
                >
                  {TIME_OPTIONS_MIN.map((option) => (
                    <MenuItem key={option} value={option}>
                      {option} min
                    </MenuItem>
                  ))}
                </TextField>
                <Button
                  variant="contained"
                  startIcon={loading ? <CircularProgress size={16} color="inherit" /> : <AutoAwesomeOutlinedIcon />}
                  onClick={findBestTask}
                  disabled={loading}
                >
                  {loading ? 'Finding…' : 'Find my best task'}
                </Button>
              </Stack>
            </Stack>
          </CardContent>
        </Card>

        {/* Error state */}
        {error && (
          <Card sx={{ border: '1px solid', borderColor: 'error.main' }}>
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
                      <Typography variant="h6">{task.title}</Typography>
                      <Chip
                        size="small"
                        label={task.priority}
                        sx={[softToneSx('error'), { fontWeight: 600 }]}
                      />
                    </Stack>
                    <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                      {task.project}
                    </Typography>

                    <Stack spacing={0.75} sx={{ mb: 3 }}>
                      <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                        {fitsWindow ? (
                          <CheckCircleOutlinedIcon fontSize="small" color="success" />
                        ) : (
                          <ScheduleOutlinedIcon fontSize="small" color="warning" />
                        )}
                        <Typography variant="body2">
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

                    <Stack direction="row" spacing={1.5} sx={{ flexWrap: 'wrap' }}>
                      <Button
                        variant="contained"
                        disabled={started || starting}
                        onClick={startTask}
                        startIcon={starting ? <CircularProgress size={16} color="inherit" /> : (!started && <PlayArrowOutlinedIcon />)}
                      >
                        {started ? 'Task started' : starting ? 'Starting…' : 'Start task'}
                      </Button>
                      <Button
                        variant="outlined"
                        component={Link}
                        href={`/employee/tasks/${task.id}?chat=1`}
                        startIcon={<ChatBubbleOutlineOutlinedIcon />}
                      >
                        Chat about it
                      </Button>
                      <Button
                        variant="outlined"
                        component={Link}
                        href={`/employee/tasks/${task.id}`}
                        endIcon={<ArrowForwardOutlinedIcon />}
                      >
                        View details
                      </Button>
                    </Stack>
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
                <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                  {task.project}
                </Typography>

                <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', mb: 3 }}>
                  <Chip
                    size="small"
                    icon={<NotificationsActiveOutlinedIcon />}
                    label={task.priority}
                    sx={softToneSx('error')}
                  />
                  <Chip
                    size="small"
                    icon={<ScheduleOutlinedIcon />}
                    label={fitsWindow ? `Fits in ${minutes} min` : `Needs ${task.effortEstimate} min`}
                    sx={softToneSx(fitsWindow ? 'info' : 'warning')}
                  />
                  {(task.dependencies ?? []).length > 0 && (
                    <Chip
                      size="small"
                      icon={<LinkOutlinedIcon />}
                      label={`${task.dependencies.length} dependenc${task.dependencies.length === 1 ? 'y' : 'ies'}`}
                      sx={softToneSx('secondary')}
                    />
                  )}
                </Stack>

                <Stack spacing={2}>
                  <DetailRow label="Description" value={task.description} />
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
                  <DetailRow label="Estimated effort" value={`${task.effortEstimate} min`} />
                  <DetailRow label="Project" value={task.project} />
                  {(task.constraints ?? []).length > 0 && (
                    <DetailRow label="Constraints" value={(task.constraints ?? []).join(', ')} />
                  )}
                </Stack>
              </CardContent>
            </Card>
          </Stack>
        )}
      </Stack>
    </PageContainer>
  )
}
