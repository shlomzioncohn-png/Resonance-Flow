'use client';

import { useEffect, useState, useCallback } from 'react';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import Button from '@mui/material/Button';
import AdminProjectDetails from '@/components/layout/AdminProjectDetails/AdminProjectDetails';
import LoadingState from '@/components/feedback/LoadingState';
import ErrorState from '@/components/feedback/ErrorState';
import EmptyState from '@/components/feedback/EmptyState';
import PageContainer from '@/components/layout/PageContainer';
import { ApiError, getErrorMessage } from '@/services/api';
import {
  getProjectDetails,
  type AdminProjectDetails as AdminProjectDetailsType,
} from '@/services/adminProjectsApi';

type LoadState = 'loading' | 'loaded' | 'not-found' | 'error';

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
        // Not every project on the Projects screen has details yet - a 404 isn't worth a retry
        if (err instanceof ApiError && err.status === 404) {
          setState('not-found');
          return;
        }
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

  if (state === 'loading') {
    return <LoadingState message="Loading project…" />;
  }

  if (state === 'error') {
    return (
      <PageContainer title="Project">
        <ErrorState description={error ?? undefined} onRetry={handleRetry} />
      </PageContainer>
    );
  }

  if (state === 'not-found' || !project) {
    return (
      <PageContainer title="Project not found">
        <EmptyState
          title="No details for this project yet"
          description="It may have been removed, or its details aren't available yet."
          action={
            <Button component={Link} href="/manager/projects" variant="outlined">
              Back to Projects
            </Button>
          }
        />
      </PageContainer>
    );
  }

  // Edit / Archive / Delete aren't passed: there's no API for them yet, so the
  // buttons stay hidden instead of doing nothing.
  return (
    <AdminProjectDetails
      projectId={project.projectId}
      projectName={project.projectName}
      description={project.description}
      status={project.status}
      startDate={project.startDate}
      endDate={project.endDate}
      totalTasks={project.totalTasks}
      inProgressTasks={project.inProgressTasks}
      blockedTasks={project.blockedTasks}
      completedTasks={project.completedTasks}
      progressPercentage={project.progressPercentage}
      processStages={project.processStages ?? []}
      team={project.team ?? []}
      recentActivities={(project.recentActivities ?? []).map((activity) => ({
        ...activity,
        // The feed calls 'info' 'default' - an unknown type would crash it
        type: activity.type === 'info' ? 'default' : activity.type,
      }))}
    />
  );
}
