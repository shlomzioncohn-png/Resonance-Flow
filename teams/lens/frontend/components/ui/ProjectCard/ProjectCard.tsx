'use client'

import Link from 'next/link'
import Avatar from '@mui/material/Avatar'
import AvatarGroup from '@mui/material/AvatarGroup'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import StatusBadge, { type StatusTone } from '../StatusBadge'

export interface ProjectCardMember {
  name: string
  avatarUrl?: string
}

export interface ProjectCardProps {
  id: string
  name: string
  team: string
  status: StatusTone
  statusLabel: string
  members: ProjectCardMember[]
  /** 0-100 */
  progressPercent: number
  totalCount: number
  inProgressCount: number
  blockedCount: number
  lastUpdatedAt: string
  href: string
}

function formatRelativeTime(iso: string): string {
  const diffMinutes = Math.round((Date.now() - new Date(iso).getTime()) / 60000)
  if (diffMinutes < 1) return 'just now'
  if (diffMinutes < 60) return `${diffMinutes}m ago`
  const diffHours = Math.round(diffMinutes / 60)
  if (diffHours < 24) return `${diffHours}h ago`
  return `${Math.round(diffHours / 24)}d ago`
}

/**
 * Card is the clickable unit via a real anchor stretched over it (not an
 * `onClick` on the Card), matching DataTable's row-link pattern — keeps the
 * card keyboard- and screen-reader-accessible instead of a div pretending
 * to be a link.
 */
export default function ProjectCard({
  id,
  name,
  team,
  status,
  statusLabel,
  members,
  progressPercent,
  totalCount,
  inProgressCount,
  blockedCount,
  lastUpdatedAt,
  href,
}: ProjectCardProps) {
  const theme = useTheme()
  const titleId = `${id}-project-name`

  return (
    <Card sx={{ position: 'relative', height: '100%' }}>
      <CardContent>
        <Stack spacing={1.5}>
          <Stack
            direction="row"
            spacing={1}
            sx={{ justifyContent: 'space-between', alignItems: 'flex-start' }}
          >
            <Typography id={titleId} variant="subtitle1" sx={{ fontWeight: 600 }}>
              {name}
            </Typography>
            <StatusBadge status={status} label={statusLabel} />
          </Stack>

          <Typography variant="body2" color="text.secondary">
            {team}
          </Typography>

          <AvatarGroup
            max={4}
            sx={{
              justifyContent: 'flex-end',
              '& .MuiAvatar-root': { width: 28, height: 28, fontSize: 13 },
            }}
          >
            {members.map((member) => (
              <Avatar key={member.name} src={member.avatarUrl} alt={member.name}>
                {member.name.charAt(0)}
              </Avatar>
            ))}
          </AvatarGroup>

          <Stack spacing={0.5}>
            <Stack direction="row" sx={{ justifyContent: 'space-between' }}>
              <Typography variant="caption" color="text.secondary">
                Progress
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {progressPercent}%
              </Typography>
            </Stack>
            <LinearProgress
              variant="determinate"
              value={progressPercent}
              sx={{
                borderRadius: theme.custom.radius.pill,
                bgcolor: theme.palette.status[status].bg,
                '& .MuiLinearProgress-bar': { bgcolor: theme.palette.status[status].main },
              }}
            />
          </Stack>

          <Typography variant="body2" color="text.secondary">
            {`${totalCount} total  •  ${inProgressCount} in progress  •  ${blockedCount} blocked`}
          </Typography>

          <Typography variant="caption" color="text.disabled">
            {`Updated ${formatRelativeTime(lastUpdatedAt)}`}
          </Typography>
        </Stack>
      </CardContent>

      <Link href={href} aria-labelledby={titleId} style={{ position: 'absolute', inset: 0 }} />
    </Card>
  )
}
