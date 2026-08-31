'use client'

import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import ArrowBackOutlinedIcon from '@mui/icons-material/ArrowBackOutlined'
import CheckCircleOutlinedIcon from '@mui/icons-material/CheckCircleOutlined'
import SendOutlinedIcon from '@mui/icons-material/SendOutlined'
import { radiusTokens } from '@/theme'
import { useTask } from '../../taskStore'

interface Message {
  id: string
  role: 'user' | 'assistant'
  text: string
}

function Bubble({ message }: { message: Message }) {
  const isUser = message.role === 'user'
  return (
    <Box
      sx={{
        alignSelf: isUser ? 'flex-end' : 'flex-start',
        maxWidth: '80%',
        px: 1.75,
        py: 1,
        borderRadius: radiusTokens.md,
        bgcolor: (theme) => (isUser ? theme.palette.primary.main : theme.palette.action.hover),
        color: isUser ? 'primary.contrastText' : 'text.primary',
      }}
    >
      <Typography variant="body2">{message.text}</Typography>
    </Box>
  )
}

// This is a fixed, scripted demo — there is no Mind/Chain conversational endpoint yet (see
// docs/employee-ui-design.md §4.B). Whatever the employee types advances the same two-step
// script; only the first reply after opening depends on whether the task is still blocked.
export default function ChatPanel({ onBack }: { onBack: () => void }) {
  const { task, clearBlockedBy } = useTask()
  // Snapshotted once at mount: clearBlockedBy() empties task.blockedBy as part of resolving
  // the script, so deriving these live from `task` would flip wasBlocked to false the moment
  // the script resolves — right when the confirmation card's `resolved && wasBlocked` needs it.
  const [{ wasBlocked, blockerTitle }] = useState(() => ({
    wasBlocked: task.blockedBy.length > 0,
    blockerTitle: task.blockedBy[0]?.title,
  }))

  const [messages, setMessages] = useState<Message[]>(
    wasBlocked
      ? [
          { id: 'seed-1', role: 'user', text: 'Why is this task blocked?' },
          {
            id: 'seed-2',
            role: 'assistant',
            text: `It's blocked because it depends on "${blockerTitle}", which is not completed yet.`,
          },
        ]
      : [
          {
            id: 'seed-1',
            role: 'assistant',
            text: "This task isn't blocked by anything right now.",
          },
        ],
  )
  const [resolved, setResolved] = useState(!wasBlocked)
  const [draft, setDraft] = useState('')

  const send = () => {
    const text = draft.trim()
    if (!text) return
    setDraft('')

    const userMessage: Message = { id: crypto.randomUUID(), role: 'user', text }

    if (!resolved) {
      const assistantMessage: Message = {
        id: crypto.randomUUID(),
        role: 'assistant',
        text: `Done. I removed the dependency on "${blockerTitle}". This task is no longer blocked.`,
      }
      setMessages((current) => [...current, userMessage, assistantMessage])
      clearBlockedBy()
      setResolved(true)
    } else {
      const assistantMessage: Message = {
        id: crypto.randomUUID(),
        role: 'assistant',
        text: "Got it — I don't have anything else to update on this task right now.",
      }
      setMessages((current) => [...current, userMessage, assistantMessage])
    }
  }

  return (
    <Stack sx={{ height: '100%', minHeight: 420 }}>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 2 }}>
        <IconButton size="small" onClick={onBack} aria-label="Back to overview">
          <ArrowBackOutlinedIcon fontSize="small" />
        </IconButton>
        <Box>
          <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
            Chat about this task
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {task.title} &middot; {task.team}
          </Typography>
        </Box>
      </Stack>

      <Stack spacing={1.25} sx={{ flexGrow: 1, py: 1 }} aria-live="polite">
        {messages.map((message) => (
          <Bubble key={message.id} message={message} />
        ))}

        {resolved && wasBlocked && (
          <Box
            sx={{
              alignSelf: 'flex-start',
              maxWidth: '80%',
              p: 1.5,
              borderRadius: radiusTokens.md,
              bgcolor: (theme) => alpha(theme.palette.success.main, 0.1),
              border: (theme) => `1px solid ${alpha(theme.palette.success.main, 0.3)}`,
            }}
          >
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
              <CheckCircleOutlinedIcon fontSize="small" color="success" />
              <Typography variant="body2" sx={{ fontWeight: 600 }}>
                Task updated
              </Typography>
            </Stack>
            <Typography variant="caption" color="text.secondary" component="div" sx={{ mb: 0.5 }}>
              Dependencies were updated.
            </Typography>
            <Button size="small" onClick={onBack} sx={{ px: 0 }}>
              View task details →
            </Button>
          </Box>
        )}
      </Stack>

      <Stack direction="row" spacing={1} sx={{ mt: 2 }}>
        <TextField
          fullWidth
          size="small"
          placeholder="Ask anything about this task..."
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.nativeEvent.isComposing) send()
          }}
        />
        <IconButton color="primary" onClick={send} aria-label="Send message">
          <SendOutlinedIcon />
        </IconButton>
      </Stack>
    </Stack>
  )
}
