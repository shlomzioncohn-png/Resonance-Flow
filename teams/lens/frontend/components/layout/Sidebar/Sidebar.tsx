'use client'

import Box from '@mui/material/Box'
import Drawer from '@mui/material/Drawer'
import List from '@mui/material/List'
import ListItemButton from '@mui/material/ListItemButton'
import ListItemIcon from '@mui/material/ListItemIcon'
import ListItemText from '@mui/material/ListItemText'
import Toolbar from '@mui/material/Toolbar'
import Typography from '@mui/material/Typography'
import Link from 'next/link'
import { usePathname } from 'next/navigation'
import Logo from '../Logo'
import { useRole } from '../RoleProvider'
import { navSections } from './navConfig'

export const SIDEBAR_WIDTH = 260

const SIDEBAR_BG = '#111827'
const SIDEBAR_TEXT = '#9ca3af'
const SIDEBAR_TEXT_ACTIVE = '#ffffff'
const SIDEBAR_DIVIDER = 'rgba(255, 255, 255, 0.08)'
const SIDEBAR_HOVER = 'rgba(255, 255, 255, 0.06)'

export interface SidebarProps {
  open: boolean
  onClose: () => void
  variant: 'permanent' | 'temporary'
}

export default function Sidebar({ open, onClose, variant }: SidebarProps) {
  const pathname = usePathname()
  const { role } = useRole()

  const visibleSections =
    role === 'manager'
      ? navSections.filter((section) => section.label !== 'Employee')
      : role === 'employee'
        ? navSections.filter((section) => section.label === 'Employee')
        : navSections

  const content = (
    <Box
      role="navigation"
      aria-label="Primary"
      sx={{
        height: '100%',
        display: 'flex',
        flexDirection: 'column',
        bgcolor: SIDEBAR_BG,
        color: SIDEBAR_TEXT,
      }}
    >
      <Toolbar sx={{ px: 2.5 }}>
        <Logo variant="dark" />
      </Toolbar>

      <Box sx={{ flexGrow: 1, overflowY: 'auto', px: 1.5, pb: 2 }}>
        {visibleSections.map((section, index) => (
          <Box key={section.label} sx={{ mt: index === 0 ? 0.5 : 2.5 }}>
            <Typography
              variant="caption"
              sx={{
                display: 'block',
                px: 1.5,
                mb: 0.5,
                color: 'rgba(255,255,255,0.35)',
                fontWeight: 700,
                letterSpacing: '0.08em',
                textTransform: 'uppercase',
              }}
            >
              {section.label}
            </Typography>
            <List disablePadding>
              {section.items.map((item) => {
                const selected = pathname === item.href
                return (
                  <ListItemButton
                    key={item.href}
                    component={Link}
                    href={item.href}
                    selected={selected}
                    onClick={variant === 'temporary' ? onClose : undefined}
                    sx={{
                      borderRadius: 1.5,
                      mb: 0.25,
                      pl: item.icon ? 1.5 : 4.5,
                      color: selected ? SIDEBAR_TEXT_ACTIVE : SIDEBAR_TEXT,
                      '&:hover': { bgcolor: SIDEBAR_HOVER },
                      '&.Mui-selected': {
                        bgcolor: 'primary.main',
                        color: SIDEBAR_TEXT_ACTIVE,
                        '&:hover': { bgcolor: 'primary.main' },
                      },
                    }}
                  >
                    {item.icon && (
                      <ListItemIcon sx={{ minWidth: 32, color: 'inherit' }}>
                        {item.icon}
                      </ListItemIcon>
                    )}
                    <ListItemText
                      primary={item.label}
                      slotProps={{
                        primary: {
                          sx: { fontSize: 14, fontWeight: selected ? 600 : 500 },
                        },
                      }}
                    />
                  </ListItemButton>
                )
              })}
            </List>
          </Box>
        ))}
      </Box>
    </Box>
  )

  if (variant === 'permanent') {
    return (
      <Box
        component="nav"
        sx={{
          width: SIDEBAR_WIDTH,
          flexShrink: 0,
          display: { xs: 'none', md: 'block' },
          borderRight: `1px solid ${SIDEBAR_DIVIDER}`,
        }}
      >
        {content}
      </Box>
    )
  }

  return (
    <Drawer
      variant="temporary"
      open={open}
      onClose={onClose}
      ModalProps={{ keepMounted: true }}
      sx={{
        display: { xs: 'block', md: 'none' },
        [`& .MuiDrawer-paper`]: { width: SIDEBAR_WIDTH, boxSizing: 'border-box', border: 'none' },
      }}
    >
      {content}
    </Drawer>
  )
}
