// For more info, see https://github.com/storybookjs/eslint-plugin-storybook#configuration-flat-config-format
import storybook from "eslint-plugin-storybook";

import nextConfig from 'eslint-config-next'
import jsxA11y from 'eslint-plugin-jsx-a11y'

// `eslint-config-next` already registers the jsx-a11y plugin but only turns
// on a small, permissive subset of its rules (as warnings). LENS's
// accessibility baseline (see docs/design-system.md) needs real enforcement,
// so the plugin's full `recommended` rule set is layered on top and wins
// over Next's subset — reusing the plugin Next already registered instead of
// registering it a second time, which ESLint's flat config rejects.
const eslintConfig = [
  ...nextConfig,
  { rules: jsxA11y.flatConfigs.recommended.rules },
  ...storybook.configs["flat/recommended"]
]

export default eslintConfig
