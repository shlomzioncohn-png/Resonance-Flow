'use client';

import { useState } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import List from '@mui/material/List';
import ListItem from '@mui/material/ListItem';
import ListItemText from '@mui/material/ListItemText';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import EmptyState from '@/components/feedback/EmptyState';
import ErrorState from '@/components/feedback/ErrorState';
import LoadingState from '@/components/feedback/LoadingState';
import type { TaskActivity as TaskActivityData } from '@/services/workspaceApi';
import { activityText, fullTime, relativeTime, sourceLabel } from './activityText';

/** "Show more" reveals this many more rows each time. */
export const ACTIVITY_PAGE_SIZE = 20;

type TaskActivityProps = {
  activity?: TaskActivityData;
  loading?: boolean;
  /** Both histories failed - nothing to show. */
  error?: string | null;
  onRetry?: () => void;
  /** For stories and tests: the "now" relative times are measured from. */
  now?: Date;
};

/**
 * The task's history from Chain, newest first, read-only. If one of the two
 * histories couldn't be loaded, the other is still shown with a note about
 * what's missing. No row names a person: Chain doesn't record one.
 */
export default function TaskActivity({
  activity,
  loading = false,
  error,
  onRetry,
  now,
}: TaskActivityProps) {
  const [visible, setVisible] = useState(ACTIVITY_PAGE_SIZE);

  if (loading) return <LoadingState message="Loading activity…" />;
  if (error || !activity) {
    return (
      <ErrorState
        title="Activity could not be loaded"
        description={error ?? undefined}
        onRetry={onRetry}
      />
    );
  }

  const missing = [
    activity.statusHistoryUnavailable && 'status changes',
    activity.dependencyHistoryUnavailable && 'dependency changes',
  ].filter(Boolean);
  const shown = activity.items.slice(0, visible);

  return (
    <Stack spacing={2}>
      {missing.length > 0 && (
        <Alert severity="warning">
          Some activity couldn&apos;t be loaded right now ({missing.join(' and ')}). Showing the
          rest.
        </Alert>
      )}

      {activity.items.length === 0 ? (
        <EmptyState
          title="No activity yet"
          description="Status, blocking and dependency changes will show up here."
        />
      ) : (
        <>
          <List disablePadding aria-label="Task activity, newest first">
            {shown.map((item) => {
              const source = sourceLabel(item);
              return (
                <ListItem key={`${item.type}-${item.id}`} divider disableGutters>
                  <ListItemText
                    primary={activityText(item)}
                    secondary={
                      <>
                        <Tooltip title={fullTime(item.createdAt)}>
                          <Box component="time" dateTime={item.createdAt} tabIndex={0}>
                            {relativeTime(item.createdAt, now)}
                          </Box>
                        </Tooltip>
                        {source && ` · ${source}`}
                      </>
                    }
                  />
                </ListItem>
              );
            })}
          </List>

          <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
            {visible < activity.items.length && (
              <Button
                variant="outlined"
                onClick={() => setVisible((count) => count + ACTIVITY_PAGE_SIZE)}
              >
                Show more
              </Button>
            )}
            <Typography variant="caption" color="text.secondary" aria-live="polite">
              Showing {shown.length} of {activity.items.length}
            </Typography>
          </Stack>
        </>
      )}
    </Stack>
  );
}
