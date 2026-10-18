import type { Components, Theme } from '@mui/material/styles';

/**
 * Component-level theme overrides. This is the *only* place styling that
 * applies to every instance of a MUI component should live — a one-off tweak
 * belongs in that component's `sx` prop, but a rule like "every Button has no
 * text-transform" belongs here so it isn't repeated (or drifts) across files.
 * See docs/design-system.md → "MUI usage conventions".
 */
export const components: Components<Theme> = {
  MuiCssBaseline: {
    styleOverrides: (theme) => ({
      body: {
        backgroundColor: theme.palette.background.default,
      },
      // Accessibility baseline: a visible, consistent focus ring for keyboard
      // navigation on every interactive element, independent of MUI's ripple.
      '*:focus-visible': {
        outline: `2px solid ${theme.palette.primary.main}`,
        outlineOffset: 2,
      },
      '@media (prefers-reduced-motion: reduce)': {
        '*': {
          animationDuration: '0.001ms !important',
          animationIterationCount: '1 !important',
          transitionDuration: '0.001ms !important',
          scrollBehavior: 'auto !important',
        },
      },
    }),
  },
  MuiAppBar: {
    defaultProps: {
      color: 'inherit',
      elevation: 0,
    },
    styleOverrides: {
      root: ({ theme }) => ({
        backgroundColor: theme.palette.background.paper,
        color: theme.palette.text.primary,
        borderBottom: `1px solid ${theme.palette.divider}`,
      }),
    },
  },
  MuiButton: {
    defaultProps: {
      disableElevation: true,
    },
    styleOverrides: {
      root: ({ theme }) => ({
        textTransform: 'none',
        fontWeight: theme.typography.button.fontWeight,
        borderRadius: theme.custom.radius.sm,
      }),
    },
  },
  MuiCard: {
    defaultProps: {
      elevation: 0,
    },
    styleOverrides: {
      root: ({ theme }) => ({
        borderRadius: theme.custom.radius.md,
        border: `1px solid ${theme.palette.divider}`,
        boxShadow: theme.custom.elevation.card,
      }),
    },
  },
  MuiPaper: {
    styleOverrides: {
      root: {
        backgroundImage: 'none',
      },
    },
  },
  MuiChip: {
    styleOverrides: {
      root: ({ theme }) => ({
        fontWeight: theme.typography.button.fontWeight,
        borderRadius: theme.custom.radius.pill,
      }),
    },
  },
  // Shared look of every table (DataTable and raw MUI tables alike).
  MuiTableCell: {
    styleOverrides: {
      root: ({ theme }) => ({
        padding: theme.spacing(1.5, 2),
        borderBottomColor: theme.palette.divider,
      }),
      head: ({ theme }) => ({
        ...theme.typography.subtitle2,
        color: theme.palette.text.secondary,
        backgroundColor: theme.palette.grey[50],
        whiteSpace: 'nowrap',
      }),
    },
  },
  MuiTableRow: {
    styleOverrides: {
      root: ({ theme }) => ({
        '&.MuiTableRow-hover:hover': {
          backgroundColor: theme.palette.action.hover,
        },
      }),
    },
  },
  MuiTooltip: {
    styleOverrides: {
      tooltip: ({ theme }) => ({
        backgroundColor: theme.palette.grey[900],
        borderRadius: theme.custom.radius.sm,
        fontSize: theme.typography.caption.fontSize,
      }),
    },
  },
  MuiListItemButton: {
    styleOverrides: {
      root: ({ theme }) => ({
        borderRadius: theme.custom.radius.sm,
      }),
    },
  },
  MuiDrawer: {
    styleOverrides: {
      paper: {
        border: 'none',
      },
    },
  },
};
