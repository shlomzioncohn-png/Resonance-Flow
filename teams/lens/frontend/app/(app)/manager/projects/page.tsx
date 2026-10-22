'use client';

import { useMemo, useState } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Stack from '@mui/material/Stack';
import PageContainer from '@/components/layout/PageContainer';
import LoadingState from '@/components/feedback/LoadingState';
import EmptyState from '@/components/feedback/EmptyState';
import ErrorState from '@/components/feedback/ErrorState';
import FilterChip, { type FilterChipOption } from '@/components/ui/FilterChip';
import KpiTile from '@/components/ui/KpiTile';
import ProjectCard from '@/components/ui/ProjectCard';
import { type Project } from '@/services/projectsApi';
import { useManagerProjects } from '@/hooks/useManagerProjects';
import { getErrorMessage } from '@/services/api';

const ALL_OPTION: FilterChipOption = { label: 'All', value: 'all' };

const STATUS_OPTIONS: FilterChipOption[] = [
  ALL_OPTION,
  { label: 'Healthy', value: 'healthy' },
  { label: 'At Risk', value: 'warning' },
  { label: 'Critical', value: 'critical' },
  { label: 'No data', value: 'neutral' },
];

const SORT_OPTIONS: FilterChipOption[] = [
  { label: 'Health', value: 'health' },
  { label: 'Name', value: 'name' },
  { label: 'Progress', value: 'progress' },
];

/** Most urgent first, per the team lead's requirement that "Sort: Health" genuinely reorders the grid. */
const HEALTH_URGENCY: Record<Project['status'], number> = {
  critical: 0,
  warning: 1,
  healthy: 2,
  neutral: 3,
};

function optionsFrom(values: string[]): FilterChipOption[] {
  const unique = Array.from(new Set(values)).sort((a, b) => a.localeCompare(b));
  return [ALL_OPTION, ...unique.map((value) => ({ label: value, value }))];
}

export default function ManagerProjectsPage() {
  const [statusFilter, setStatusFilter] = useState('all');
  const [teamFilter, setTeamFilter] = useState('all');
  const [ownerFilter, setOwnerFilter] = useState('all');
  const [sort, setSort] = useState('health');

  // React Query hook handles polling every 15s, retries, and background refetches.
  // isPending reflects only the initial load, not background refetches.
  const { data: projects = [], isPending, isError, error, refetch } = useManagerProjects();

  const teamOptions = useMemo(() => optionsFrom(projects.map((p) => p.team)), [projects]);
  const ownerOptions = useMemo(() => optionsFrom(projects.map((p) => p.owner)), [projects]);

  const visibleProjects = useMemo(() => {
    const filtered = projects.filter(
      (project) =>
        (statusFilter === 'all' || project.status === statusFilter) &&
        (teamFilter === 'all' || project.team === teamFilter) &&
        (ownerFilter === 'all' || project.owner === ownerFilter),
    );

    return [...filtered].sort((a, b) => {
      if (sort === 'name') return a.name.localeCompare(b.name);
      if (sort === 'progress') return b.progressPercent - a.progressPercent;
      return HEALTH_URGENCY[a.status] - HEALTH_URGENCY[b.status];
    });
  }, [projects, statusFilter, teamFilter, ownerFilter, sort]);

  const resetFilters = () => {
    setStatusFilter('all');
    setTeamFilter('all');
    setOwnerFilter('all');
  };

  const healthyCount = projects.filter((p) => p.status === 'healthy').length;
  const warningCount = projects.filter((p) => p.status === 'warning').length;
  const criticalCount = projects.filter((p) => p.status === 'critical').length;

  return (
    <PageContainer
      title="Projects"
      description="Every active project, at a glance — open one to see process status and the people on it."
    >
      {isPending && <LoadingState message="Loading projects…" />}

      {isError && (
        <ErrorState description={getErrorMessage(error)} onRetry={() => refetch()} />
      )}

      {!isPending && (
        <>
          <Stack
            direction={{ xs: 'column', sm: 'row' }}
            spacing={2}
            sx={{
              justifyContent: 'space-between',
              alignItems: { xs: 'flex-start', sm: 'center' },
              mb: 3,
            }}
          >
            <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', gap: 1 }}>
              <FilterChip
                label="Status"
                options={STATUS_OPTIONS}
                value={statusFilter}
                onChange={setStatusFilter}
              />
              <FilterChip
                label="Team"
                options={teamOptions}
                value={teamFilter}
                onChange={setTeamFilter}
              />
              <FilterChip
                label="Owner"
                options={ownerOptions}
                value={ownerFilter}
                onChange={setOwnerFilter}
              />
            </Stack>
            <FilterChip label="Sort" options={SORT_OPTIONS} value={sort} onChange={setSort} />
          </Stack>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ mb: 4 }}>
            <KpiTile value={projects.length} label="Total projects" tone="neutral" />
            <KpiTile value={healthyCount} label="Healthy" tone="healthy" />
            <KpiTile value={warningCount} label="At risk" tone="warning" />
            <KpiTile value={criticalCount} label="Critical" tone="critical" />
          </Stack>

          {visibleProjects.length === 0 ? (
            <EmptyState
              title="No projects match your filters"
              description="Try a different status, team, or owner."
              action={
                <Button variant="outlined" onClick={resetFilters}>
                  Clear filters
                </Button>
              }
            />
          ) : (
            <Box
              sx={{
                display: 'grid',
                gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)', md: 'repeat(3, 1fr)' },
                gap: 3,
              }}
            >
              {visibleProjects.map((project) => (
                <ProjectCard
                  key={project.id}
                  id={project.id}
                  name={project.name}
                  team={project.team}
                  status={project.status}
                  statusLabel={project.statusLabel}
                  members={project.members}
                  progressPercent={project.progressPercent}
                  totalCount={project.totalCount}
                  inProgressCount={project.inProgressCount}
                  blockedCount={project.blockedCount}
                  lastUpdatedAt={project.lastUpdatedAt}
                  href={`/manager/projects/${project.id}`}
                />
              ))}
            </Box>
          )}
        </>
      )}
    </PageContainer>
  );
}
