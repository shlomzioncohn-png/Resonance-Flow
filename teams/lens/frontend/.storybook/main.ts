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
  framework: '@storybook/nextjs-vite',
  // Serves public/mockServiceWorker.js, the Service Worker MSW registers in stories
  staticDirs: ['../public'],
};
export default config;
