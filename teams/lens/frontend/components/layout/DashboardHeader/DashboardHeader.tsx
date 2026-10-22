'use client';

import { useState, type MouseEvent } from 'react';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import InputBase from '@mui/material/InputBase';
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import AutoAwesomeOutlinedIcon from '@mui/icons-material/AutoAwesomeOutlined';
import FiberManualRecordIcon from '@mui/icons-material/FiberManualRecord';
import FilterChip from '@/components/ui/FilterChip';
import type { DashboardFilters } from '@/services/dashboardFilters';

type ProjectOption = {
  id: string;
  name: string;
};

type DashboardHeaderProps = {
  orgName: string;
  filters: DashboardFilters;
  /** Every chip change goes through here — the same entry point the LLM will use. */
  onApplyFilters: (partial: Partial<DashboardFilters>) => void;
  projects: ProjectOption[];
  /** Seconds since the dashboard data was last refreshed. */
  updatedSecondsAgo: number;
  /**
   * Shows the reserved "Ask" text field. It stays disabled until the LLM
   * filter parser exists; the flag only decides whether its slot is visible.
   */
  showAsk?: boolean;
};

const PERIOD_OPTIONS = [
  { label: 'Last 7 days', value: '7d' },
  { label: 'Last 30 days', value: '30d' },
  { label: 'Last 90 days', value: '90d' },
];

function formatUpdated(seconds: number): string {
  if (seconds < 60) return `${seconds}s ago`;
  return `${Math.floor(seconds / 60)}m ago`;
}

export default function DashboardHeader({
  orgName,
  filters,
  onApplyFilters,
  projects,
  updatedSecondsAgo,
  showAsk = false,
}: DashboardHeaderProps) {
  const [projectMenuAnchor, setProjectMenuAnchor] = useState<HTMLElement | null>(null);

  // One project at a time for now: the dashboard endpoints filter by a single project,
  // so the chip never shows a selection the tiles can't apply.
  const selectedProjectId = filters.projectIds?.[0];
  // A project from a shared link still shows, even before (or without) the project list.
  const selectedProjectName =
    projects.find((project) => project.id === selectedProjectId)?.name ?? selectedProjectId;

  const openProjectMenu = (event: MouseEvent<HTMLElement>) =>
    setProjectMenuAnchor(event.currentTarget);
  const closeProjectMenu = () => setProjectMenuAnchor(null);
  const handleSelectProject = (projectId: string) => {
    onApplyFilters({ projectIds: [projectId] });
    closeProjectMenu();
  };

  return (
    <Stack spacing={2}>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1}
        sx={{ justifyContent: 'space-between', alignItems: { xs: 'flex-start', sm: 'center' } }}
      >
        <Typography variant="h5" component="h1" sx={{ fontWeight: 700 }}>
          {orgName}
        </Typography>
        <Chip
          size="small"
          variant="outlined"
          icon={<FiberManualRecordIcon color="success" sx={{ fontSize: 10 }} />}
          label={`Auto-refresh · updated ${formatUpdated(updatedSecondsAgo)}`}
          sx={{ color: 'text.secondary' }}
        />
      </Stack>

      {showAsk && (
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 1,
            bgcolor: 'grey.100',
            borderRadius: 2,
            px: 1.5,
            py: 0.75,
            maxWidth: 520,
          }}
        >
          <AutoAwesomeOutlinedIcon fontSize="small" sx={{ color: 'text.secondary' }} />
          <InputBase
            placeholder="Ask: blocked tasks in Project X this week"
            disabled
            fullWidth
            inputProps={{ 'aria-label': 'Ask about the dashboard (coming soon)' }}
            sx={{ fontSize: 14 }}
          />
        </Box>
      )}

      <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 1 }}>
        <FilterChip
          label="Period"
          options={PERIOD_OPTIONS}
          value={filters.period}
          onChange={(value) => onApplyFilters({ period: value as DashboardFilters['period'] })}
        />

        {/* Click to change the project, the X (or Delete key) to go back to all projects. */}
        <Chip
          variant="outlined"
          clickable
          label={`Project: ${selectedProjectName ?? 'All'}`}
          onClick={openProjectMenu}
          onDelete={selectedProjectId ? () => onApplyFilters({ projectIds: [] }) : undefined}
          aria-haspopup="listbox"
          aria-expanded={Boolean(projectMenuAnchor)}
        />
        <Menu
          anchorEl={projectMenuAnchor}
          open={Boolean(projectMenuAnchor)}
          onClose={closeProjectMenu}
        >
          {projects.map((project) => (
            <MenuItem
              key={project.id}
              selected={project.id === selectedProjectId}
              onClick={() => handleSelectProject(project.id)}
            >
              {project.name}
            </MenuItem>
          ))}
        </Menu>
      </Stack>
    </Stack>
  );
}
