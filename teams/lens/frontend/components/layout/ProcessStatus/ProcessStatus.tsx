'use client';

import Box from '@mui/material/Box';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Typography from '@mui/material/Typography';
import Stack from '@mui/material/Stack';
import { useTheme } from '@mui/material/styles';
import { alpha } from '@mui/material/styles';

export interface ProcessStage {
  name: string;
  count: number;
  color?: string;
}

export interface ProcessStatusProps {
  stages: ProcessStage[];
  /** Optional title for the section */
  title?: string;
}

/**
 * ProcessStatus Component
 *
 * Displays a visual representation of workflow stages with task counts.
 * Shows stages like Planning, In Progress, Review, Testing, Done.
 * 
 * Accessibility:
 * - Uses semantic list markup (role="list", role="listitem")
 * - Each stage has an aria-label describing its name and task count
 * - Colors are not the only indicator: stage names and numbers provide semantic meaning
 */
export default function ProcessStatus({ stages, title = 'Process Status' }: ProcessStatusProps) {
  const theme = useTheme();
  
  // Map stage index to theme tokens - no hardcoded colors
  const getDefaultColor = (index: number): { main: string; bg: string; text: string } => {
    const colorMap = [
      // Planning - neutral/gray
      { main: theme.palette.status.neutral.main, bg: theme.palette.status.neutral.bg, text: theme.palette.status.neutral.text },
      // In Progress - primary/blue
      { main: theme.palette.primary.main, bg: alpha(theme.palette.primary.main, 0.08), text: theme.palette.primary.main },
      // Review - warning/orange
      { main: theme.palette.status.warning.main, bg: theme.palette.status.warning.bg, text: theme.palette.status.warning.text },
      // Testing - info/cyan (fallback to primary if info not available)
      { main: theme.palette.info?.main || theme.palette.primary.main, bg: alpha(theme.palette.info?.main || theme.palette.primary.main, 0.08), text: theme.palette.info?.main || theme.palette.primary.main },
      // Done - healthy/green
      { main: theme.palette.status.healthy.main, bg: theme.palette.status.healthy.bg, text: theme.palette.status.healthy.text },
    ];
    return colorMap[index % colorMap.length];
  };

  return (
    <Card>
      <CardContent>
        <Typography variant="h6" component="h2" sx={{ mb: 3 }}>
          {title}
        </Typography>
        <Box 
          sx={{ display: 'flex', gap: 1.5, overflowX: 'auto', pb: 1 }}
          role="list"
          aria-label={title}
        >
          {stages.map((stage, index) => {
            // Like the default stages: a light tint behind, the color itself for the text.
            // (bg === text made the stage's name and count invisible.)
            const customColor = stage.color
              ? { main: stage.color, bg: alpha(stage.color, 0.08), text: stage.color }
              : null;
            const colors = customColor || getDefaultColor(index);
            const mainColor = customColor?.main || colors.main;
            const bgColor = colors.bg;
            const textColor = colors.text;

            return (
              <Card
                key={stage.name}
                variant="outlined"
                role="listitem"
                aria-label={`${stage.name}: ${stage.count} tasks`}
                sx={{
                  bgcolor: bgColor,
                  border: `2px solid ${mainColor}`,
                  textAlign: 'center',
                  minWidth: '140px',
                  flex: '0 0 140px',
                }}
              >
                <CardContent sx={{ py: 2, '&:last-child': { pb: 2 } }}>
                  <Typography
                    variant="caption"
                    sx={{
                      display: 'block',
                      textTransform: 'uppercase',
                      fontSize: '0.7rem',
                      fontWeight: 600,
                      color: textColor,
                      mb: 1,
                    }}
                  >
                    {stage.name}
                    {index === 1 && (
                      <Box
                        component="span"
                        sx={{
                          display: 'inline-block',
                          ml: 0.5,
                          px: 0.75,
                          py: 0.25,
                          bgcolor: mainColor,
                          color: 'white',
                          borderRadius: 0.5,
                          fontSize: '0.65rem',
                          fontWeight: 700,
                        }}
                      >
                        ACTIVE
                      </Box>
                    )}
                  </Typography>
                  <Typography
                    variant="h4"
                    component="p"
                    sx={{
                      color: textColor,
                      fontWeight: 'bold',
                      mb: 0.5,
                    }}
                  >
                    {stage.count}
                  </Typography>
                  <Typography
                    variant="caption"
                    sx={{
                      display: 'block',
                      color: theme.palette.text.secondary,
                      fontSize: '0.75rem',
                    }}
                  >
                    tasks
                  </Typography>
                </CardContent>
              </Card>
            );
          })}
        </Box>
      </CardContent>
    </Card>
  );
}
