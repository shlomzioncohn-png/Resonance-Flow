'use client'

import { useState } from 'react'
import Link from 'next/link'
import Avatar from '@mui/material/Avatar'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Chip from '@mui/material/Chip'
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
import GitHubIcon from '@mui/icons-material/GitHub'
import LinkOutlinedIcon from '@mui/icons-material/LinkOutlined'
import NotificationsActiveOutlinedIcon from '@mui/icons-material/NotificationsActiveOutlined'
import PersonOutlineIcon from '@mui/icons-material/PersonOutlined'
import PlayArrowOutlinedIcon from '@mui/icons-material/PlayArrowOutlined'
import ScheduleOutlinedIcon from '@mui/icons-material/ScheduleOutlined'
import PageContainer from '@/components/layout/PageContainer'
import { radiusTokens } from '@/theme'
import { DetailRow, iconBadgeSx, softToneSx } from './shared'
import { useTask } from './taskStore'

const TIME_OPTIONS_MIN = [15, 30, 45, 60, 90]

export default function EmployeeWorkspacePage() {
  const [minutes, setMinutes] = useState(45)
  const [recommended, setRecommended] = useState(false)
  const [started, setStarted] = useState(false)

  const findBestTask = () => {
    setStarted(false)
    setRecommended(true)
  }

  const { task } = useTask()

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
                  startIcon={<AutoAwesomeOutlinedIcon />}
                  onClick={findBestTask}
                >
                  Find my best task
                </Button>
              </Stack>
            </Stack>
          </CardContent>
        </Card>

        {recommended && (
          <Stack direction={{ xs: 'column', lg: 'row' }} spacing={3} sx={{ alignItems: 'stretch' }}>
            <Card sx={{ flex: 1, minWidth: 0 }}>
              <CardContent>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 2 }}>
                  <AutoAwesomeOutlinedIcon fontSize="small" color="primary" />
                  <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
                    Best task for you
                  </Typography>
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
                      {task.team}
                    </Typography>

                    <Stack spacing={0.75} sx={{ mb: 3 }}>
                      {[
                        `Fits in your ${minutes} minute window`,
                        'High importance',
                        `Unblocks ${task.blocks.length} other tasks`,
                      ].map((reason) => (
                        <Stack key={reason} direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                          <CheckCircleOutlinedIcon fontSize="small" color="success" />
                          <Typography variant="body2">{reason}</Typography>
                        </Stack>
                      ))}
                    </Stack>

                    <Stack direction="row" spacing={1.5} sx={{ flexWrap: 'wrap' }}>
                      <Button
                        variant="contained"
                        disabled={started}
                        onClick={() => setStarted(true)}
                        startIcon={!started && <PlayArrowOutlinedIcon />}
                      >
                        {started ? 'Task started' : 'Start task'}
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
                    sx={[softToneSx('success'), { fontWeight: 600 }]}
                  />
                </Stack>
                <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                  {task.team}
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
                    label={`Fits in ${minutes} min`}
                    sx={softToneSx('info')}
                  />
                  <Chip
                    size="small"
                    icon={<LinkOutlinedIcon />}
                    label={`Unblocks ${task.blocks.length} tasks`}
                    sx={softToneSx('secondary')}
                  />
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
                  <DetailRow label="Due" value={task.due} />
                  <DetailRow label="Estimated effort" value={`${task.estimatedMinutes} min`} />
                  <DetailRow label="Created" value={task.createdAt} />
                  <DetailRow
                    label="Source"
                    value={
                      <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
                        <GitHubIcon sx={{ fontSize: 16 }} />
                        {task.source}
                      </Stack>
                    }
                  />
                  <DetailRow label="Sprint" value={task.sprint} />
                </Stack>
              </CardContent>
            </Card>
          </Stack>
        )}
      </Stack>
    </PageContainer>
  )
}
