import { aiTokens, elevationTokens, radiusTokens } from './tokens';

/**
 * Tokens that don't have a home in MUI's built-in theme shape (MUI's own
 * `theme.shape` only covers a single `borderRadius` number, and it has no
 * elevation concept beyond the 25-step shadow array). Exposed as
 * `theme.custom.*` via the module augmentation below, so components read
 * `theme.custom.radius.lg` instead of a local magic number.
 */
export interface LensCustomTokens {
  radius: typeof radiusTokens;
  elevation: typeof elevationTokens;
  ai: typeof aiTokens;
}

declare module '@mui/material/styles' {
  interface Theme {
    custom: LensCustomTokens;
  }
  interface ThemeOptions {
    custom?: LensCustomTokens;
  }
}

export const custom: LensCustomTokens = {
  radius: radiusTokens,
  elevation: elevationTokens,
  ai: aiTokens,
};
