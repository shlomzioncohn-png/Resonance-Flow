'use client';

import type { ReactNode } from 'react';
import Box from '@mui/material/Box';
import Card from '@mui/material/Card';
import CardActionArea from '@mui/material/CardActionArea';
import CardContent from '@mui/material/CardContent';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import Link from 'next/link';
import Logo from '@/components/layout/Logo';
import { useRole, type Role } from '@/components/layout/RoleProvider';
import PersonOutlineIcon from '@mui/icons-material/PersonOutlined';
import SupervisorAccountOutlinedIcon from '@mui/icons-material/SupervisorAccountOutlined';

const entryPoints: {
  href: string;
  role: Role;
  label: string;
  description: string;
  icon: ReactNode;
}[] = [
  {
    href: '/manager',
    role: 'manager',
    label: 'Manager',
    description: 'Team workload, blocked work, and bottlenecks.',
    icon: <SupervisorAccountOutlinedIcon sx={{ fontSize: 28 }} />,
  },
  {
    href: '/employee',
    role: 'employee',
    label: 'Employee',
    description: 'Your own workload snapshot and blocked work.',
    icon: <PersonOutlineIcon sx={{ fontSize: 28 }} />,
  },
];

export default function LoginPage() {
  const { setRole } = useRole();

  return (
    <Box
      sx={{
        minHeight: '100vh',
        display: 'grid',
        placeItems: 'center',
        bgcolor: 'background.default',
        p: 2,
      }}
    >
      <Paper
        sx={{ p: 4, maxWidth: 460, width: '100%', textAlign: 'center' }}
        elevation={0}
        variant="outlined"
      >
        <Stack spacing={2.5} sx={{ alignItems: 'center' }}>
          <Logo variant="light" />
          <Typography variant="body2" color="text.secondary">
            Authentication is not implemented yet. Manager and employee will have separate sign-in
            flows — for now, pick a zone to preview it.
          </Typography>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ width: '100%' }}>
            {entryPoints.map((entry) => (
              <Card key={entry.href} sx={{ flex: 1 }}>
                <CardActionArea
                  component={Link}
                  href={entry.href}
                  onClick={() => setRole(entry.role)}
                  sx={{ height: '100%', p: 2 }}
                >
                  <CardContent sx={{ p: '0 !important' }}>
                    <Box sx={{ color: 'primary.main', mb: 1 }}>{entry.icon}</Box>
                    <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
                      Continue as {entry.label}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      {entry.description}
                    </Typography>
                  </CardContent>
                </CardActionArea>
              </Card>
            ))}
          </Stack>
        </Stack>
      </Paper>
    </Box>
  );
}
