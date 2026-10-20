import type { StorybookConfig } from '@storybook/nextjs-vite';

const config: StorybookConfig = {
  stories: [
    '../app/**/*.stories.@(js|jsx|mjs|ts|tsx)',
    '../components/**/*.stories.@(js|jsx|mjs|ts|tsx)',
  ],
  addons: [
    '@storybook/addon-vitest',
    '@storybook/addon-a11y',
    '@storybook/addon-docs',
    '@storybook/addon-mcp',
    'msw-storybook-addon',
  ],
  // Serves public/mockServiceWorker.js, which MSW registers in the browser.
  staticDirs: ['../public'],
  framework: '@storybook/nextjs-vite',
};
export default config;
