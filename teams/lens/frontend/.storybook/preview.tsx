import type { Preview } from '@storybook/nextjs-vite';
import CssBaseline from '@mui/material/CssBaseline';
import { ThemeProvider } from '@mui/material/styles';
import { setupWorker } from 'msw/browser';
import { mswLoader } from 'msw-storybook-addon/csf3';
import { theme } from '@/theme';
import { RoleProvider } from '@/components/layout/RoleProvider';
import { handlers } from '@/mocks/handlers';
import '../app/globals.css';

const preview: Preview = {
  // MSW answers API calls in every story with the shared handlers in mocks/handlers.ts.
  // A story can override one in its own beforeEach: `msw.use(http.post(...))`.
  loaders: [
    mswLoader(async () => {
      const worker = setupWorker(...handlers);
      // Anything without a handler (Storybook's own requests) goes to the network as usual
      await worker.start({ onUnhandledRequest: 'bypass', quiet: true });
      return worker;
    }),
  ],
  // Every story starts signed out. RoleProvider keeps the role in localStorage, and all
  // stories share one browser, so a story that signs in (Pages/Login) would otherwise
  // leave its role behind for the next one.
  beforeEach() {
    window.localStorage.removeItem('lens.role');
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
