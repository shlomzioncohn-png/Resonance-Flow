'use client'

import AppBar from '@mui/material/AppBar'
import Avatar from '@mui/material/Avatar'
import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import InputBase from '@mui/material/InputBase'
import Stack from '@mui/material/Stack'
import Toolbar from '@mui/material/Toolbar'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import Link from 'next/link'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import MenuIcon from '@mui/icons-material/Menu'
import NotificationsNoneOutlinedIcon from '@mui/icons-material/NotificationsNoneOutlined'
import SearchIcon from '@mui/icons-material/Search'
import { useRole } from '../RoleProvider'

export interface HeaderProps {
  onMenuClick: () => void
}

const ROLE_DISPLAY = {
  manager: { initials: 'MG', title: 'Manager' },
  employee: { initials: 'EM', title: 'Employee' },
} as const

export default function Header({ onMenuClick }: HeaderProps) {
  const { role } = useRole()
  const display = role ? ROLE_DISPLAY[role] : { initials: 'LN', title: 'Guest user' }

  return (
    <AppBar position="sticky" elevation={0}>
      <Toolbar sx={{ gap: 1.5 }}>
        <IconButton
          color="inherit"
          aria-label="Open navigation"
          edge="start"
          onClick={onMenuClick}
          sx={{ display: { md: 'none' } }}
        >
          <MenuIcon />
        </IconButton>

        <Box
          sx={{
            display: { xs: 'none', sm: 'flex' },
            alignItems: 'center',
            gap: 1,
            bgcolor: '#f1f3f6',
            borderRadius: 2,
            px: 1.5,
            py: 0.75,
            flexGrow: 1,
            maxWidth: 360,
          }}
        >
          <SearchIcon fontSize="small" sx={{ color: 'text.secondary' }} />
          <InputBase
            placeholder="Search tasks, projects…"
            disabled
            fullWidth
            sx={{ fontSize: 14 }}
          />
        </Box>

        <Box sx={{ flexGrow: 1 }} />

        <Tooltip title="Notifications — not implemented yet">
          <IconButton>
            <NotificationsNoneOutlinedIcon />
          </IconButton>
        </Tooltip>

        <Tooltip title="Switch role — authentication is not implemented yet">
          <Stack
            component={Link}
            href="/login"
            direction="row"
            spacing={1}
            sx={{ alignItems: 'center', pl: 0.5, textDecoration: 'none', color: 'inherit' }}
          >
            <Avatar sx={{ width: 32, height: 32, bgcolor: 'primary.main', fontSize: 14 }}>
              {display.initials}
            </Avatar>
            <Box sx={{ display: { xs: 'none', sm: 'block' } }}>
              <Typography variant="body2" sx={{ fontWeight: 600, lineHeight: 1.2 }}>
                {display.title}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {role ? 'Switch role' : 'No role assigned'}
              </Typography>
            </Box>
            <ExpandMoreIcon
              fontSize="small"
              sx={{ color: 'text.secondary', display: { xs: 'none', sm: 'block' } }}
            />
          </Stack>
        </Tooltip>
      </Toolbar>
    </AppBar>
  )
}
