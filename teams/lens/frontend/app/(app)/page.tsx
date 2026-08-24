'use client'

import Box from '@mui/material/Box'
import Card from '@mui/material/Card'
import CardActionArea from '@mui/material/CardActionArea'
import CardContent from '@mui/material/CardContent'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import Link from 'next/link'
import PageContainer from '@/components/layout/PageContainer'

const zones = [
  {
    href: '/manager',
    title: 'Manager workspace',
    description: 'Team workload, work in progress, blocked work, and bottlenecks.',
  },
  {
    href: '/employee',
    title: 'Employee overview',
    description: 'Your own workload snapshot and blocked work within the team context.',
  },
]

export default function DashboardPage() {
  return (
    <PageContainer
      title="LENS is running"
      description="Frontend foundation — App Shell and routing skeleton — is ready for the next slice."
    >
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
        {zones.map((zone) => (
          <Card key={zone.href} sx={{ flex: 1 }}>
            <CardActionArea component={Link} href={zone.href} sx={{ height: '100%' }}>
              <CardContent>
                <Typography variant="h6" component="h2" gutterBottom>
                  {zone.title}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  {zone.description}
                </Typography>
              </CardContent>
            </CardActionArea>
          </Card>
        ))}
      </Stack>
      <Box sx={{ mt: 4 }}>
        <Typography variant="caption" color="text.disabled">
          Authentication and role-based access are not implemented yet — every route is reachable
          directly during this foundation phase.
        </Typography>
      </Box>
    </PageContainer>
  )
}
