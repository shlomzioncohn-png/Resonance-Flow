import { defineConfig } from 'vitest/config';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { storybookTest } from '@storybook/addon-vitest/vitest-plugin';
import { playwright } from '@vitest/browser-playwright';
const dirname =
  typeof __dirname !== 'undefined' ? __dirname : path.dirname(fileURLToPath(import.meta.url));

// More info at: https://storybook.js.org/docs/next/writing-tests/integrations/vitest-addon
export default defineConfig({
  resolve: {
    alias: {
      '@': path.resolve(import.meta.dirname, '.'),
    },
  },
  test: {
    projects: [
      {
        extends: true,
        test: {
          environment: 'node',
          include: ['tests/**/*.test.ts'],
        },
      },
      {
        extends: true,
        plugins: [
          // The plugin will run tests for the stories defined in your Storybook config
          // See options at: https://storybook.js.org/docs/next/writing-tests/integrations/vitest-addon#storybooktest
          storybookTest({
            configDir: path.join(dirname, '.storybook'),
          }),
        ],
        // Scan every source file up front, so Vite pre-bundles all dependencies
        // before the first story runs. Without this it discovers them one story
        // at a time (each `@mui/material/<Component>` is its own entry) and reloads
        // the browser mid-run - on a cold cache, i.e. every CI run, that fails or
        // hangs whichever tests were in flight.
        optimizeDeps: {
          entries: [
            '.storybook/**/*.{ts,tsx}',
            'app/**/*.{ts,tsx}',
            'components/**/*.{ts,tsx}',
            'design-system/**/*.{ts,tsx}',
            'services/**/*.{ts,tsx}',
            'theme/**/*.{ts,tsx}',
          ],
        },
        test: {
          name: 'storybook',
          browser: {
            enabled: true,
            headless: true,
            provider: playwright({}),
            instances: [
              {
                browser: 'chromium',
              },
            ],
          },
        },
      },
    ],
  },
});
