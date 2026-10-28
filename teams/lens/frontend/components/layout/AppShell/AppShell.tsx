'use client';

import { useEffect, useState, type ReactNode } from 'react';
import Box from '@mui/material/Box';
import { usePathname, useRouter } from 'next/navigation';
import LoadingState from '@/components/feedback/LoadingState';
import Header from '../Header';
import { useRole } from '../RoleProvider';
import Sidebar from '../Sidebar';
import { canAccess } from '../Sidebar/navConfig';

export interface AppShellProps {
  children: ReactNode;
}

export default function AppShell({ children }: AppShellProps) {
  const [mobileOpen, setMobileOpen] = useState(false);
  const { role, ready } = useRole();
  const pathname = usePathname();
  const router = useRouter();

  // Every screen in the shell needs a signed-in user with the right role -
  // typing a URL must not get around the sign-in or the role.
  const allowed = role !== null && canAccess(role, pathname);

  useEffect(() => {
    if (!ready || allowed) return;
    router.replace(role ? '/unauthorized' : '/login');
  }, [ready, allowed, role, router]);

  // Don't flash the screen while waiting to redirect
  if (!ready || !allowed) return <LoadingState />;

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
