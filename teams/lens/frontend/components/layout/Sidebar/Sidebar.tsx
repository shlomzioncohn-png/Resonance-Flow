'use client';

import { alpha, useTheme } from '@mui/material/styles';
import {
  Box,
  Drawer,
  List,
  ListItem,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Toolbar,
  Typography,
} from '@/design-system/components';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import Logo from '../Logo';
import { useRole } from '../RoleProvider';
import { navSections } from './navConfig';

export const SIDEBAR_WIDTH = 260;

export interface SidebarProps {
  open: boolean;
  onClose: () => void;
  variant: 'permanent' | 'temporary';
}

export default function Sidebar({ open, onClose, variant }: SidebarProps) {
  const pathname = usePathname();
  const { role } = useRole();
  const theme = useTheme();

  const sidebarBg = theme.palette.sidebar.background;
  const sidebarText = theme.palette.sidebar.text;
  const sidebarTextActive = theme.palette.sidebar.textActive;
  const sidebarDivider = theme.palette.sidebar.divider;
  const sidebarHover = theme.palette.sidebar.hover;

  // Signed out: nothing - AppShell is about to send the user to /login anyway
  const visibleSections = role ? navSections.filter((section) => section.roles.includes(role)) : [];

  const content = (
    <Box
      role="navigation"
      aria-label="Primary"
      sx={{
        height: '100%',
        display: 'flex',
        flexDirection: 'column',
        bgcolor: sidebarBg,
        color: sidebarText,
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
                color: alpha(theme.palette.common.white, 0.55),
                fontWeight: 700,
                letterSpacing: '0.08em',
                textTransform: 'uppercase',
              }}
            >
              {section.label}
            </Typography>
            <List disablePadding>
              {section.items.map((item) => {
                const selected = pathname === item.href;
                return (
                  <ListItem key={item.href} disablePadding>
                    <ListItemButton
                      component={Link}
                      href={item.href}
                      selected={selected}
                      onClick={variant === 'temporary' ? onClose : undefined}
                      sx={{
                        borderRadius: 1.5,
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
                            sx: { fontSize: 14, fontWeight: selected ? 600 : 500 },
                          },
                        }}
                      />
                    </ListItemButton>
                  </ListItem>
                );
              })}
            </List>
          </Box>
        ))}
      </Box>
    </Box>
  );

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
    );
  }

  return (
    <Drawer
      variant="temporary"
      open={open}
      onClose={onClose}
      ModalProps={{ keepMounted: true }}
      slotProps={{ paper: { 'aria-label': 'Navigation menu' } }}
      sx={{
        display: { xs: 'block', md: 'none' },
        [`& .MuiDrawer-paper`]: { width: SIDEBAR_WIDTH, boxSizing: 'border-box', border: 'none' },
      }}
    >
      {content}
    </Drawer>
  );
}
