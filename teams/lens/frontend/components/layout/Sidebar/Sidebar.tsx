'use client'

import Box from '@mui/material/Box'
import Drawer from '@mui/material/Drawer'
import List from '@mui/material/List'
import ListItemButton from '@mui/material/ListItemButton'
import ListItemIcon from '@mui/material/ListItemIcon'
import ListItemText from '@mui/material/ListItemText'
import Toolbar from '@mui/material/Toolbar'
import Typography from '@mui/material/Typography'
import { alpha, useTheme } from '@mui/material/styles'
import Link from 'next/link'
import { usePathname } from 'next/navigation'
import Logo from '../Logo'
import { navSections } from './navConfig'

export const SIDEBAR_WIDTH = 260

export interface SidebarProps {
  open: boolean
  onClose: () => void
  variant: 'permanent' | 'temporary'
}

export default function Sidebar({ open, onClose, variant }: SidebarProps) {
  const pathname = usePathname()
  const theme = useTheme()

  // The sidebar is a permanently dark surface regardless of app-wide theme
  // mode — a deliberate product choice, not a one-off color. It still reads
  // every color from the theme (the slate scale + alpha of common.white)
  // instead of hardcoding hex, so a future dark-mode or rebrand only changes
  // `theme/tokens.ts`.
  const sidebarText = alpha(theme.palette.common.white, 0.65)
  const sidebarTextActive = theme.palette.common.white
  const sidebarDivider = alpha(theme.palette.common.white, 0.08)
  const sidebarHover = alpha(theme.palette.common.white, 0.06)

  const content = (
    <Box
      role="navigation"
      aria-label="Primary"
      sx={{
        height: '100%',
        display: 'flex',
        flexDirection: 'column',
        bgcolor: 'grey.900',
        color: sidebarText,
      }}
    >
      <Toolbar sx={{ px: 2.5 }}>
        <Logo variant="dark" />
      </Toolbar>

      <Box sx={{ flexGrow: 1, overflowY: 'auto', px: 1.5, pb: 2 }}>
        {navSections.map((section, index) => (
          <Box key={section.label} sx={{ mt: index === 0 ? 0.5 : 2.5 }}>
            <Typography
              variant="overline"
              sx={{
                display: 'block',
                px: 1.5,
                mb: 0.5,
                color: alpha(theme.palette.common.white, 0.35),
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
                      mb: 0.25,
                      pl: item.icon ? 1.5 : 4.5,
                      color: selected ? sidebarTextActive : sidebarText,
                      '&:hover': { bgcolor: sidebarHover },
                      '&.Mui-selected': {
                        bgcolor: 'primary.main',
                        color: sidebarTextActive,
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
                          sx: {
                            fontSize: 14,
                            fontWeight: selected
                              ? theme.typography.subtitle2.fontWeight
                              : theme.typography.body2.fontWeight,
                          },
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
          borderRight: `1px solid ${sidebarDivider}`,
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
