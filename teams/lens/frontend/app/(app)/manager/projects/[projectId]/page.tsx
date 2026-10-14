'use client';

import { useEffect, useState, useCallback } from 'react';
import { useParams } from 'next/navigation';
import Box from '@mui/material/Box';
import CircularProgress from '@mui/material/CircularProgress';
import Typography from '@mui/material/Typography';
import Alert from '@mui/material/Alert';
import AdminProjectDetails from '@/components/layout/AdminProjectDetails/AdminProjectDetails';
import { getErrorMessage } from '@/services/api';
import {
  getProjectDetails,
  type AdminProjectDetails as AdminProjectDetailsType,
} from '@/services/adminProjectsApi';

type LoadState = 'loading' | 'loaded' | 'error';

/**
 * Project Details Page
 *
 * Displays full project details for manager view
 * Fetches from /api/admin/projects/{projectId}
 */
export default function AdminProjectDetailsPage() {
  const params = useParams();
  const projectId = params.projectId as string;

  const [state, setState] = useState<LoadState>('loading');
  const [project, setProject] = useState<AdminProjectDetailsType | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Only sets state once the request settles - the initial 'loading' comes
  // from useState, and handleRetry resets it, so nothing is set synchronously
  // inside the effect.
  const fetchProject = useCallback(() => {
    getProjectDetails(projectId)
      .then((data) => {
        setProject(data);
        setState('loaded');
      })
      .catch((err) => {
        setError(getErrorMessage(err));
        setState('error');
      });
  }, [projectId]);

  // The effect body never calls setState synchronously — the loading
  // transition for a retry happens in handleRetry instead.
  useEffect(() => {
    fetchProject();
  }, [fetchProject]);

  const handleRetry = () => {
    setState('loading');
    setError(null);
    fetchProject();
  };

  const handleEdit = () => {
    console.log('Edit project:', projectId);
    // TODO: Implement edit functionality
  };

  const handleArchive = () => {
    console.log('Archive project:', projectId);
    // TODO: Implement archive functionality
  };

  const handleDelete = () => {
    if (window.confirm('Are you sure you want to delete this project?')) {
      console.log('Delete project:', projectId);
      // TODO: Implement delete functionality
    }
  };

  if (state === 'loading') {
    return (
      <Box
        sx={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '400px' }}
      >
        <CircularProgress />
      </Box>
    );
  }

  if (state === 'error') {
    return (
      <Box sx={{ p: 3 }}>
        <Alert severity="error" onClose={handleRetry}>
          {error}
        </Alert>
      </Box>
    );
  }

  if (!project) {
    return (
      <Box sx={{ p: 3 }}>
        <Typography color="textSecondary">Project not found</Typography>
      </Box>
    );
  }

  return (
    <AdminProjectDetails
      projectId={project.projectId}
      projectName={project.projectName}
      description={project.description}
      status={project.status as 'healthy' | 'warning' | 'critical'}
      startDate={project.startDate}
      endDate={project.endDate}
      totalTasks={project.totalTasks}
      inProgressTasks={project.inProgressTasks}
      blockedTasks={project.blockedTasks}
      completedTasks={project.completedTasks}
      progressPercentage={project.progressPercentage}
      processStages={(project.processStages as Array<{ name: string; count: number }>).map(
        (stage) => ({
          ...stage,
        }),
      )}
      team={(
        project.team as Array<{
          id: string;
          name: string;
          role: string;
          avatar?: string;
          activeTasks?: number;
          workload?: 'Available' | 'At Capacity' | 'Overloaded';
        }>
      ).map((member) => ({
        id: member.id,
        name: member.name,
        role: member.role,
        avatar: member.avatar,
        activeTasks: member.activeTasks,
        workload: member.workload as 'Available' | 'At Capacity' | 'Overloaded' | undefined,
      }))}
      recentActivities={(
        project.recentActivities as Array<{
          id: string;
          actor: { name: string; avatar?: string };
          action: string;
          description: string;
          timestamp: string;
          type?: 'success' | 'warning' | 'error' | 'info';
        }>
      ).map((activity) => ({
        id: activity.id,
        actor: activity.actor,
        action: activity.action,
        description: activity.description,
        timestamp: activity.timestamp,
        type: activity.type as 'default' | 'success' | 'warning' | 'error' | undefined,
      }))}
      onEdit={handleEdit}
      onArchive={handleArchive}
      onDelete={handleDelete}
    />
  );
}
