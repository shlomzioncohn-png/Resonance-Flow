'use client';

import AppBar from '@mui/material/AppBar';
import Avatar from '@mui/material/Avatar';
import Box from '@mui/material/Box';
import IconButton from '@mui/material/IconButton';
import InputBase from '@mui/material/InputBase';
import Stack from '@mui/material/Stack';
import Toolbar from '@mui/material/Toolbar';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { useRouter } from 'next/navigation';
import LogoutIcon from '@mui/icons-material/Logout';
import MenuIcon from '@mui/icons-material/Menu';
import NotificationsNoneOutlinedIcon from '@mui/icons-material/NotificationsNoneOutlined';
import SearchIcon from '@mui/icons-material/Search';
import { logout } from '@/services/authApi';
import { useRole } from '../RoleProvider';

export interface HeaderProps {
  onMenuClick: () => void;
}

const ROLE_TITLE = {
  TENANT_ADMIN: 'Tenant Admin',
  TEAM_LEAD: 'Team Lead',
  EMPLOYEE: 'Employee',
} as const;

/** "Maya Manager" -> "MM" */
function initialsOf(name: string): string {
  return name
    .split(' ')
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0].toUpperCase())
    .join('');
}

export default function Header({ onMenuClick }: HeaderProps) {
  const { user, signOut } = useRole();
  const router = useRouter();

  const handleSignOut = async () => {
    try {
      await logout();
    } catch {
      // Sign out here anyway - the cookie is also gone when it expires
    }
    signOut();
    router.replace('/login');
  };

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

        {user && (
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center', pl: 0.5 }}>
            <Avatar sx={{ width: 32, height: 32, bgcolor: 'primary.main', fontSize: 14 }}>
              {initialsOf(user.displayName)}
            </Avatar>
            <Box sx={{ display: { xs: 'none', sm: 'block' } }}>
              <Typography variant="body2" sx={{ fontWeight: 600, lineHeight: 1.2 }}>
                {user.displayName}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {ROLE_TITLE[user.role]}
              </Typography>
            </Box>
            <Tooltip title="Sign out">
              <IconButton aria-label="Sign out" onClick={handleSignOut}>
                <LogoutIcon fontSize="small" />
              </IconButton>
            </Tooltip>
          </Stack>
        )}
      </Toolbar>
    </AppBar>
  );
}
