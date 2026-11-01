import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import List from '@mui/material/List';
import ListItem from '@mui/material/ListItem';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import type { Constraint } from '@/services/workspaceApi';
import { softToneSx } from '../../shared';
import {
  constraintStatusLabel,
  constraintTypeLabel,
  isOpenConstraint,
  reasonEntries,
} from './constraintText';

/**
 * The task's constraints from Chain, read-only: one row each with a type chip,
 * a status chip and the reason. Open (PENDING) ones stand out; satisfied or
 * waived ones are greyed out. Nothing here changes a constraint's status.
 */
export default function TaskConstraints({ constraints }: { constraints?: Constraint[] }) {
  const items = constraints ?? [];

  return (
    <Box component="section" aria-labelledby="task-constraints-heading">
      <Typography
        id="task-constraints-heading"
        variant="subtitle2"
        color="text.secondary"
        component="h2"
        sx={{ mb: 1 }}
      >
        Constraints
      </Typography>

      {items.length === 0 ? (
        <Typography variant="body2" color="text.secondary">
          No constraints on this task
        </Typography>
      ) : (
        <List disablePadding aria-label="Task constraints">
          {items.map((constraint) => {
            const open = isOpenConstraint(constraint);
            const reason = reasonEntries(constraint.reason);
            return (
              <ListItem
                key={constraint.constraintId}
                divider
                disableGutters
                data-open={open}
                sx={{ display: 'block', py: 1, color: open ? 'text.primary' : 'text.secondary' }}
              >
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
                  <Chip
                    size="small"
                    label={constraintTypeLabel(constraint.type)}
                    variant={open ? 'filled' : 'outlined'}
                    sx={open ? { fontWeight: 600 } : { color: 'text.secondary' }}
                  />
                  <Chip
                    size="small"
                    label={constraintStatusLabel(constraint.status)}
                    variant={open ? 'filled' : 'outlined'}
                    sx={open ? [softToneSx('error'), { fontWeight: 600 }] : { color: 'text.secondary' }}
                  />
                </Stack>
                {reason.length > 0 && (
                  <Box
                    component="ul"
                    sx={{ m: 0, mt: 0.75, pl: 2.5, typography: 'body2', color: 'inherit' }}
                  >
                    {reason.map((line) => (
                      <li key={line}>{line}</li>
                    ))}
                  </Box>
                )}
              </ListItem>
            );
          })}
        </List>
      )}
    </Box>
  );
}
