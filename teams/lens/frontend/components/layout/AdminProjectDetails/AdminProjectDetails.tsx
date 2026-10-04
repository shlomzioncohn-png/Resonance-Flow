'use client';

import Box from '@mui/material/Box';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Typography from '@mui/material/Typography';
import Button from '@mui/material/Button';
import Stack from '@mui/material/Stack';
import Avatar from '@mui/material/Avatar';
import Chip from '@mui/material/Chip';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import EditIcon from '@mui/icons-material/Edit';
import ArchiveIcon from '@mui/icons-material/Archive';
import DeleteIcon from '@mui/icons-material/Delete';
import StatusBadge from '@/components/ui/StatusBadge/StatusBadge';
import KpiTile from '@/components/ui/KpiTile/KpiTile';
import ProcessStatus from '@/components/layout/ProcessStatus/ProcessStatus';
import ActivityFeed from '@/components/layout/ActivityFeed/ActivityFeed';
import type { Activity } from '@/components/layout/ActivityFeed/ActivityFeed';
import type { ProcessStage } from '@/components/layout/ProcessStatus/ProcessStatus';

export interface TeamMember {
  id: string;
  name: string;
  role: string;
  avatar?: string;
  activeTasks?: number;
  workload?: 'Available' | 'At Capacity' | 'Overloaded';
}

export interface AdminProjectDetailsProps {
  projectId: string;
  projectName: string;
  description: string;
  status: 'healthy' | 'warning' | 'critical';
  startDate?: string;
  endDate?: string;
  totalTasks: number;
  inProgressTasks: number;
  blockedTasks: number;
  completedTasks: number;
  progressPercentage: number;
  processStages: ProcessStage[];
  team: TeamMember[];
  recentActivities: Activity[];
  onEdit?: () => void;
  onArchive?: () => void;
  onDelete?: () => void;
}

const getWorkloadColor = (workload?: string): 'success' | 'warning' | 'error' => {
  if (workload === 'Available') return 'success';
  if (workload === 'At Capacity') return 'warning';
  return 'error';
};

const getStatusLabel = (status: string): string => {
  if (status === 'healthy') return 'Healthy';
  if (status === 'warning') return 'At Risk';
  return 'Critical';
};

/**
 * Admin Project Details Component
 *
 * Full project dashboard showing:
 * - Project header with status and dates
 * - KPI tiles (Total, In Progress, Blocked, Completed, % Complete)
 * - Process status visualization (Planning, In Progress, Review, Testing, Done)
 * - Team members table with roles and workload
 * - Recent activity feed
 *
 * Styled consistently with Manager Dashboard
 */
export default function AdminProjectDetails({
  projectId,
  projectName,
  description,
  status,
  startDate,
  endDate,
  totalTasks,
  inProgressTasks,
  blockedTasks,
  completedTasks,
  progressPercentage,
  processStages,
  team,
  recentActivities,
  onEdit,
  onArchive,
  onDelete,
}: AdminProjectDetailsProps) {
  const progressPercentageCalc =
    totalTasks > 0 ? Math.round((completedTasks / totalTasks) * 100) : 0;

  return (
    <Box sx={{ width: '100%', p: 3 }}>
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: '1fr 320px' }, gap: 3 }}>
        {/* Main Content */}
        <Box>
          {/* Header with Status and Actions */}
          <Box
            sx={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'flex-start',
              mb: 3,
            }}
          >
            <Box>
              <Typography variant="h4" component="h1" sx={{ mb: 1, fontWeight: 600 }}>
                {projectName}
              </Typography>
              <Typography variant="body2" color="textSecondary" sx={{ mb: 1 }}>
                {description}
              </Typography>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 1 }}>
                <StatusBadge status={status} label={getStatusLabel(status)} />
                {startDate && endDate && (
                  <Typography variant="body2" color="textSecondary">
                    {startDate} – {endDate}
                  </Typography>
                )}
              </Box>
            </Box>
            <Stack direction="row" spacing={1}>
              {onEdit && (
                <Button variant="outlined" size="small" startIcon={<EditIcon />} onClick={onEdit}>
                  Edit
                </Button>
              )}
              {onArchive && (
                <Button
                  variant="outlined"
                  size="small"
                  color="warning"
                  startIcon={<ArchiveIcon />}
                  onClick={onArchive}
                >
                  Archive
                </Button>
              )}
              {onDelete && (
                <Button
                  variant="outlined"
                  size="small"
                  color="error"
                  startIcon={<DeleteIcon />}
                  onClick={onDelete}
                >
                  Delete
                </Button>
              )}
            </Stack>
          </Box>

          {/* KPI Tiles - Horizontal Layout (Manager Dashboard Style) */}
          <Stack
            direction={{ xs: 'column', sm: 'row' }}
            spacing={2}
            sx={{ mb: 4, overflowX: 'auto', pb: 1 }}
          >
            <KpiTile value={totalTasks} label="Total Tasks" tone="neutral" />
            <KpiTile value={inProgressTasks} label="In Progress" tone="warning" />
            <KpiTile value={blockedTasks} label="Blocked" tone="critical" />
            <KpiTile value={completedTasks} label="Completed" tone="healthy" />
            <KpiTile value={progressPercentageCalc} label="% Complete" tone="neutral" />
          </Stack>

          {/* Process Status Section */}
          <Box sx={{ mb: 4 }}>
            <ProcessStatus stages={processStages} />
          </Box>

          {/* Team Members Section */}
          <Card>
            <CardContent>
              <Typography variant="h6" sx={{ fontWeight: 600, mb: 2 }}>
                Team
              </Typography>
              <TableContainer>
                <Table>
                  <TableHead>
                    <TableRow sx={{ bgcolor: '#f9fafb' }}>
                      <TableCell sx={{ fontWeight: 600, color: '#374151' }}>Member</TableCell>
                      <TableCell sx={{ fontWeight: 600, color: '#374151' }}>Role</TableCell>
                      <TableCell align="center" sx={{ fontWeight: 600, color: '#374151' }}>
                        Active Tasks
                      </TableCell>
                      <TableCell align="right" sx={{ fontWeight: 600, color: '#374151' }}>
                        Workload
                      </TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {team.length > 0 ? (
                      team.map((member) => (
                        <TableRow key={member.id} hover sx={{ '&:last-child td': { border: 0 } }}>
                          <TableCell>
                            <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
                              <Avatar
                                src={member.avatar}
                                alt={member.name}
                                sx={{ width: 36, height: 36 }}
                              >
                                {member.name.charAt(0)}
                              </Avatar>
                              <Typography variant="body2" sx={{ fontWeight: 500 }}>
                                {member.name}
                              </Typography>
                            </Stack>
                          </TableCell>
                          <TableCell>
                            <Typography variant="body2" color="textSecondary">
                              {member.role}
                            </Typography>
                          </TableCell>
                          <TableCell align="center">
                            <Typography variant="body2" sx={{ fontWeight: 500, color: '#f97316' }}>
                              {member.activeTasks || 0}
                            </Typography>
                          </TableCell>
                          <TableCell align="right">
                            <Chip
                              label={member.workload || 'Available'}
                              size="small"
                              color={getWorkloadColor(member.workload)}
                              variant="outlined"
                            />
                          </TableCell>
                        </TableRow>
                      ))
                    ) : (
                      <TableRow>
                        <TableCell colSpan={4} align="center" sx={{ py: 3 }}>
                          <Typography variant="body2" color="textSecondary">
                            No team members assigned yet.
                          </Typography>
                        </TableCell>
                      </TableRow>
                    )}
                  </TableBody>
                </Table>
              </TableContainer>
            </CardContent>
          </Card>
        </Box>

        {/* Sidebar - Recent Activity */}
        <Box>
          <ActivityFeed activities={recentActivities} title="Recent Activity" maxItems={10} />
        </Box>
      </Box>
    </Box>
  );
}
