'use client'

import { useState } from 'react'
import Link from 'next/link'
import { useSearchParams } from 'next/navigation'
import Avatar from '@mui/material/Avatar'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Chip from '@mui/material/Chip'
import Container from '@mui/material/Container'
import IconButton from '@mui/material/IconButton'
import Stack from '@mui/material/Stack'
import Tab from '@mui/material/Tab'
import Tabs from '@mui/material/Tabs'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import ArrowBackOutlinedIcon from '@mui/icons-material/ArrowBackOutlined'
import ChatBubbleOutlineOutlinedIcon from '@mui/icons-material/ChatBubbleOutlineOutlined'
import ChevronRightOutlinedIcon from '@mui/icons-material/ChevronRightOutlined'
import GitHubIcon from '@mui/icons-material/GitHub'
import MoreHorizOutlinedIcon from '@mui/icons-material/MoreHorizOutlined'
import PersonOutlineIcon from '@mui/icons-material/PersonOutlined'
import { radiusTokens } from '@/theme'
import type { TaskRef } from '../../mockTask'
import { DetailRow, softToneSx } from '../../shared'
import { useTask } from '../../taskStore'
import ChatPanel from './ChatPanel'

function DependencyRow({ dependency }: { dependency: TaskRef }) {
  return (
    <Stack
      direction="row"
      spacing={1}
      sx={{
        alignItems: 'center',
        justifyContent: 'space-between',
        p: 1,
        borderRadius: radiusTokens.sm,
        bgcolor: 'action.hover',
      }}
    >
      <Box>
        <Typography variant="body2">{dependency.title}</Typography>
        <Typography variant="caption" color="text.secondary">
          {dependency.owner}
        </Typography>
      </Box>
      <ChevronRightOutlinedIcon fontSize="small" sx={{ color: 'text.disabled' }} />
    </Stack>
  )
}

export default function TaskDetailsPage() {
  // No Inbox/Chain task endpoint exists yet — every task id resolves to the same shared
  // mock task (see ../../taskStore.tsx), which the chat panel can mutate.
  const { task } = useTask()
  const searchParams = useSearchParams()
  const [mode, setMode] = useState<'overview' | 'chat'>(
    searchParams.get('chat') === '1' ? 'chat' : 'overview',
  )

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
            <Typography variant="h4">{task.title}</Typography>
            <Chip size="small" label={task.status} sx={[softToneSx('success'), { fontWeight: 600 }]} />
          </Stack>
          <Typography variant="body1" color="text.secondary">
            {task.team}
          </Typography>
        </Box>

        <Stack direction="row" spacing={1}>
          <Button variant="outlined" disabled>
            Edit task
          </Button>
          <IconButton disabled>
            <MoreHorizOutlinedIcon />
          </IconButton>
        </Stack>
      </Stack>

      <Tabs value="overview" onChange={() => {}} sx={{ mt: 2, mb: 3 }}>
        <Tab value="overview" label="Overview" />
        <Tab value="dependencies" label={`Dependencies (${task.blockedBy.length + task.blocks.length})`} disabled />
        <Tab value="activity" label="Activity" disabled />
        <Tab value="notes" label="Notes" disabled />
        <Tab value="attachments" label="Attachments" disabled />
      </Tabs>

      <Stack direction={{ xs: 'column', lg: 'row' }} spacing={3} sx={{ alignItems: 'flex-start' }}>
        <Card sx={{ flex: 1, minWidth: 0, width: '100%' }}>
          <CardContent>
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
                  <DetailRow label="Due" value={task.due} />
                  <DetailRow
                    label="Priority"
                    value={<Chip size="small" label={task.priority} sx={softToneSx('error')} />}
                  />
                  <DetailRow label="Estimated effort" value={`${task.estimatedMinutes} min`} />
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
                </Box>

                <Typography variant="subtitle2" color="text.secondary" sx={{ mb: 1 }}>
                  Tags
                </Typography>
                <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', mb: 3 }}>
                  {task.tags.map((tag) => (
                    <Chip key={tag} size="small" variant="outlined" label={tag} />
                  ))}
                  <Chip size="small" variant="outlined" label="+" disabled />
                </Stack>

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

        <Stack spacing={3} sx={{ width: { xs: '100%', lg: 360 }, flexShrink: 0 }}>
          <Card>
            <CardContent>
              <Stack
                direction="row"
                sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 2 }}
              >
                <Typography variant="subtitle2">
                  Dependencies ({task.blockedBy.length + task.blocks.length})
                </Typography>
                <Button size="small" disabled>
                  Edit
                </Button>
              </Stack>

              {task.blockedBy.length > 0 && (
                <>
                  <Typography variant="caption" color="text.secondary">
                    This task is blocked by:
                  </Typography>
                  <Stack spacing={1} sx={{ mt: 1, mb: 2 }}>
                    {task.blockedBy.map((dependency) => (
                      <DependencyRow key={dependency.id} dependency={dependency} />
                    ))}
                  </Stack>
                </>
              )}

              <Typography variant="caption" color="text.secondary">
                This task blocks:
              </Typography>
              <Stack spacing={1} sx={{ mt: 1 }}>
                {task.blocks.map((dependency) => (
                  <DependencyRow key={dependency.id} dependency={dependency} />
                ))}
              </Stack>
            </CardContent>
          </Card>

          <Card>
            <CardContent>
              <Stack
                direction="row"
                sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 1 }}
              >
                <Typography variant="subtitle2">Blocking reason</Typography>
                <Button size="small" disabled>
                  Edit
                </Button>
              </Stack>
              <Typography variant="body2" color="text.secondary">
                {task.blockingReason ?? 'No blocking reason recorded.'}
              </Typography>
            </CardContent>
          </Card>
        </Stack>
      </Stack>
    </Container>
  )
}
