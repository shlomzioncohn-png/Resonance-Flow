import type { Preview } from '@storybook/nextjs-vite';
import { mswLoader } from 'msw-storybook-addon/csf3';
import { worker } from '@/mocks/browser';
import { resetMockUsers } from '@/mocks/handlers';
import CssBaseline from '@mui/material/CssBaseline';
import { ThemeProvider } from '@mui/material/styles';
import { theme } from '@/theme';
import { RoleProvider } from '@/components/layout/RoleProvider';
import '../app/globals.css';

const preview: Preview = {
  // MSW with the app's own handlers (mocks/handlers.ts) - the same fake Gateway
  // as `NEXT_PUBLIC_API_MOCKING=enabled`. Handlers given to setupWorker() survive
  // the addon's reset between stories; a story's own `msw.use(...)` overrides don't.
  loaders: [
    mswLoader(async () => {
      await worker.start({ quiet: true, onUnhandledRequest: 'bypass' });
      return worker;
    }),
  ],
  // Every story starts from the same 12 users, whatever the story before it created or edited.
  beforeEach: () => {
    resetMockUsers();
  },
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
