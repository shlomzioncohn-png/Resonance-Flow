'use client';

import { useState, type MouseEvent } from 'react';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import InputBase from '@mui/material/InputBase';
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import AddIcon from '@mui/icons-material/Add';
import AutoAwesomeOutlinedIcon from '@mui/icons-material/AutoAwesomeOutlined';
import FiberManualRecordIcon from '@mui/icons-material/FiberManualRecord';
import FilterChip from '@/components/ui/FilterChip';

type Period = '7d' | '30d' | '90d';

type ProjectOption = {
  id: string;
  name: string;
};

type DashboardHeaderProps = {
  orgName: string;
  period: Period;
  projects: ProjectOption[];
  selectedProjectIds: string[];
  /** Seconds since the dashboard data was last refreshed. */
  updatedSecondsAgo: number;
  /**
   * Shows the reserved "Ask" text field. It stays disabled until the LLM
   * filter parser exists; the flag only decides whether its slot is visible.
   */
  showAsk?: boolean;
  onPeriodChange: (period: Period) => void;
  onAddProject: (projectId: string) => void;
  onRemoveProject: (projectId: string) => void;
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
  period,
  projects,
  selectedProjectIds,
  updatedSecondsAgo,
  showAsk = false,
  onPeriodChange,
  onAddProject,
  onRemoveProject,
}: DashboardHeaderProps) {
  const [projectMenuAnchor, setProjectMenuAnchor] = useState<HTMLElement | null>(null);

  const selectedProjects = projects.filter((project) => selectedProjectIds.includes(project.id));
  const addableProjects = projects.filter((project) => !selectedProjectIds.includes(project.id));

  const openProjectMenu = (event: MouseEvent<HTMLElement>) =>
    setProjectMenuAnchor(event.currentTarget);
  const closeProjectMenu = () => setProjectMenuAnchor(null);
  const handleAddProject = (projectId: string) => {
    onAddProject(projectId);
    closeProjectMenu();
  };

  return (
    <Stack spacing={2} sx={{ mb: 3 }}>
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
          value={period}
          onChange={(value) => onPeriodChange(value as Period)}
        />

        {selectedProjects.map((project) => (
          <Chip
            key={project.id}
            variant="outlined"
            label={`Project: ${project.name}`}
            onDelete={() => onRemoveProject(project.id)}
          />
        ))}

        {addableProjects.length > 0 && (
          <>
            <Chip
              variant="outlined"
              clickable
              icon={<AddIcon />}
              label={selectedProjects.length === 0 ? 'Project: All' : 'Add project'}
              onClick={openProjectMenu}
              aria-haspopup="listbox"
              aria-expanded={Boolean(projectMenuAnchor)}
            />
            <Menu
              anchorEl={projectMenuAnchor}
              open={Boolean(projectMenuAnchor)}
              onClose={closeProjectMenu}
            >
              {addableProjects.map((project) => (
                <MenuItem key={project.id} onClick={() => handleAddProject(project.id)}>
                  {project.name}
                </MenuItem>
              ))}
            </Menu>
          </>
        )}
      </Stack>
    </Stack>
  );
}
