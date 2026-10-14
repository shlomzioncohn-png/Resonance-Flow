'use client';

import Box from '@mui/material/Box';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Typography from '@mui/material/Typography';
import Avatar from '@mui/material/Avatar';
import Stack from '@mui/material/Stack';
import Chip from '@mui/material/Chip';

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

const typeColorMap = {
  default: { bg: '#f3f4f6', icon: '#6b7280' },
  success: { bg: '#d1fae5', icon: '#10b981' },
  warning: { bg: '#fef3c7', icon: '#f59e0b' },
  error: { bg: '#fee2e2', icon: '#ef4444' },
};

/**
 * ActivityFeed Component
 *
 * Displays a timeline of recent activities/events in a project.
 * Shows who did what and when.
 */
export default function ActivityFeed({
  activities,
  title = 'Recent Activity',
  maxItems = 10,
}: ActivityFeedProps) {
  const displayedActivities = activities.slice(0, maxItems);

  return (
    <Card>
      <CardContent>
        <Typography variant="h6" component="h2" sx={{ mb: 2 }}>
          {title}
        </Typography>

        {displayedActivities.length > 0 ? (
          <Stack spacing={2}>
            {displayedActivities.map((activity) => {
              const colors = typeColorMap[activity.type || 'default'];

              return (
                <Box
                  key={activity.id}
                  sx={{
                    display: 'flex',
                    gap: 2,
                    pb: 2,
                    borderBottom: '1px solid #e5e7eb',
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
