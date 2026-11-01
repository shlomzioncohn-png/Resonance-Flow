import LockOutlinedIcon from '@mui/icons-material/LockOutlined';
import Chip from '@mui/material/Chip';
import { alpha } from '@mui/material/styles';

/**
 * "🔒 N" for a task with open (PENDING) constraints - the same look as the employee
 * screens' OpenConstraintsChip (app/(app)/employee/shared.tsx), which components/ can't
 * import. Nothing when there are none, or when the count is unknown.
 */
export default function OpenConstraintsChip({ count }: { count?: number }) {
  if (!count || count <= 0) return null;
  const label = `${count} open constraint${count === 1 ? '' : 's'}`;
  return (
    <Chip
      size="small"
      icon={<LockOutlinedIcon />}
      label={count}
      aria-label={label}
      title={label}
      data-testid="open-constraints-chip"
      sx={(theme) => ({
        fontWeight: 600,
        bgcolor: alpha(theme.palette.warning.main, 0.12),
        color: theme.palette.warning.dark,
        '& .MuiChip-icon': { color: theme.palette.warning.main },
      })}
    />
  );
}
