'use client';

import Box from '@mui/material/Box';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Typography from '@mui/material/Typography';
import Avatar from '@mui/material/Avatar';
import Stack from '@mui/material/Stack';
import Chip from '@mui/material/Chip';
import { useTheme } from '@mui/material/styles';
import InfoIcon from '@mui/icons-material/Info';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import WarningAmberIcon from '@mui/icons-material/WarningAmber';
import ErrorIcon from '@mui/icons-material/Error';

export interface Activity {
  id: string;
  actor: {
    name: string;
    avatar?: string;
  };
  action: string;
  description?: string;
  timestamp: string;
  icon?: string;
  type?: 'default' | 'success' | 'warning' | 'error';
}

export interface ActivityFeedProps {
  activities: Activity[];
  /** Optional title for the section */
  title?: string;
  /** Maximum number of activities to display */
  maxItems?: number;
}

/**
 * Get semantic icon and label for activity type
 * Accessibility: Icons are supplemented with hidden text labels to ensure
 * type is not communicated by color alone
 */
const getActivityTypeConfig = (type: 'default' | 'success' | 'warning' | 'error') => {
  const config = {
    default: { icon: InfoIcon, label: 'Info' },
    success: { icon: CheckCircleIcon, label: 'Success' },
    warning: { icon: WarningAmberIcon, label: 'Warning' },
    error: { icon: ErrorIcon, label: 'Error' },
  };
  return config[type];
};

/**
 * ActivityFeed Component
 *
 * Displays a timeline of recent activities/events in a project.
 * Shows who did what and when.
 * 
 * Accessibility:
 * - Each activity type has both a color and an icon
 * - Hidden text labels describe the activity type
 * - Colors are from theme tokens, not hardcoded
 */
export default function ActivityFeed({
  activities,
  title = 'Recent Activity',
  maxItems = 10,
}: ActivityFeedProps) {
  const theme = useTheme();
  const displayedActivities = activities.slice(0, maxItems);

  const getTypeColors = (type: 'default' | 'success' | 'warning' | 'error') => {
    const colorMap = {
      default: { 
        bg: theme.palette.status.neutral.bg, 
        icon: theme.palette.status.neutral.text,
        border: theme.palette.status.neutral.main,
      },
      success: { 
        bg: theme.palette.status.healthy.bg, 
        icon: theme.palette.status.healthy.main,
        border: theme.palette.status.healthy.main,
      },
      warning: { 
        bg: theme.palette.status.warning.bg, 
        icon: theme.palette.status.warning.main,
        border: theme.palette.status.warning.main,
      },
      error: { 
        bg: theme.palette.status.critical.bg, 
        icon: theme.palette.status.critical.main,
        border: theme.palette.status.critical.main,
      },
    };
    return colorMap[type];
  };

  return (
    <Card>
      <CardContent>
        <Typography variant="h6" component="h2" sx={{ mb: 2 }}>
          {title}
        </Typography>

        {displayedActivities.length > 0 ? (
          <Stack spacing={2}>
            {displayedActivities.map((activity) => {
              const activityType = activity.type || 'default';
              const colors = getTypeColors(activityType);
              const typeConfig = getActivityTypeConfig(activityType);
              const IconComponent = typeConfig.icon;

              return (
                <Box
                  key={activity.id}
                  sx={{
                    display: 'flex',
                    gap: 2,
                    pb: 2,
                    borderBottom: `1px solid ${theme.palette.divider}`,
                    '&:last-child': { borderBottom: 'none', pb: 0 },
                  }}
                >
                  <Avatar
                    src={activity.actor.avatar}
                    alt={activity.actor.name}
                    sx={{ width: 40, height: 40 }}
                  >
                    {activity.actor.name.charAt(0)}
                  </Avatar>

                  <Box sx={{ flex: 1 }}>
                    <Stack direction="row" spacing={1} sx={{ mb: 0.5, alignItems: 'center' }}>
                      <Typography variant="subtitle2" component="p">{activity.actor.name}</Typography>
                      <Chip label={activity.action} size="small" variant="outlined" />
                      {/* Hidden label for screen readers: communicates activity type without relying on color */}
                      <Box
                        component="span"
                        sx={{ 
                          position: 'absolute', 
                          left: '-9999px',
                          width: '1px',
                          height: '1px',
                          overflow: 'hidden',
                        }}
                      >
                        {typeConfig.label}
                      </Box>
                      {/* Visual icon indicator - supplements color as status indicator */}
                      <IconComponent 
                        sx={{ 
                          width: 20, 
                          height: 20, 
                          color: colors.icon,
                          flexShrink: 0,
                        }} 
                        aria-hidden="true"
                      />
                    </Stack>

                    {activity.description && (
                      <Typography variant="body2" color="textSecondary" sx={{ mb: 0.5 }}>
                        {activity.description}
                      </Typography>
                    )}

                    <Typography variant="caption" color="textSecondary">
                      {activity.timestamp}
                    </Typography>
                  </Box>
                </Box>
              );
            })}
          </Stack>
        ) : (
          <Typography variant="body2" color="textSecondary">
            No recent activity.
          </Typography>
        )}
      </CardContent>
    </Card>
  );
}
