'use client';

import { useState, type ReactNode } from 'react';
import Box from '@mui/material/Box';
import Header from '../Header';
import Sidebar from '../Sidebar';

export interface AppShellProps {
  children: ReactNode;
}

export default function AppShell({ children }: AppShellProps) {
  const [mobileOpen, setMobileOpen] = useState(false);

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh' }}>
      <Sidebar variant="permanent" open onClose={() => setMobileOpen(false)} />
      <Sidebar variant="temporary" open={mobileOpen} onClose={() => setMobileOpen(false)} />

      <Box sx={{ flexGrow: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        <Header onMenuClick={() => setMobileOpen((prev) => !prev)} />
        <Box component="main" sx={{ flexGrow: 1, bgcolor: 'background.default' }}>
          {children}
        </Box>
      </Box>
    </Box>
  );
}
