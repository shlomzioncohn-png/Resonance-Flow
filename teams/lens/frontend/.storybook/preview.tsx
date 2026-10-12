import type { Preview } from '@storybook/nextjs-vite';
import CssBaseline from '@mui/material/CssBaseline';
import { ThemeProvider } from '@mui/material/styles';
import { theme } from '@/theme';
import { RoleProvider } from '@/components/layout/RoleProvider';
import '../app/globals.css';

const preview: Preview = {
  parameters: {
    controls: {
      matchers: {
        color: /(background|color)$/i,
        date: /Date$/i,
      },
    },

    a11y: {
      // 'todo' - show a11y violations in the test UI only
      // 'error' - fail CI on a11y violations
      // 'off' - skip a11y checks entirely
      test: 'error',
    },
  },
  // Real provider tree, matching app/layout.tsx: MUI theme (the single LENS
  // theme from `theme/`, not a fresh createTheme()) + CssBaseline, and
  // RoleProvider since Header/Sidebar read role via useRole().
  decorators: [
    (Story) => (
      <ThemeProvider theme={theme}>
        <CssBaseline />
        <RoleProvider>
          <Story />
        </RoleProvider>
      </ThemeProvider>
    ),
  ],
};

export default preview;
