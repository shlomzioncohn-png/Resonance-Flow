import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import InboxOutlinedIcon from '@mui/icons-material/InboxOutlined'

export interface EmptyStateProps {
  title?: string
  description?: string
  action?: ReactNode
}

export default function EmptyState({
  title = 'Nothing here yet',
  description,
  action,
}: EmptyStateProps) {
  return (
    <Box
      sx={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        textAlign: 'center',
        gap: 1,
        py: 8,
        px: 2,
        color: 'text.secondary',
      }}
    >
      <Box
        sx={{
          width: 56,
          height: 56,
          borderRadius: '50%',
          bgcolor: 'grey.100',
          display: 'grid',
          placeItems: 'center',
          mb: 1,
        }}
      >
        <InboxOutlinedIcon sx={{ fontSize: 26, color: 'text.disabled' }} />
      </Box>
      <Typography variant="h6" color="text.primary">
        {title}
      </Typography>
      {description && (
        <Typography variant="body2" sx={{ maxWidth: 360 }}>
          {description}
        </Typography>
      )}
      {action && <Box sx={{ mt: 2 }}>{action}</Box>}
    </Box>
  )
}
