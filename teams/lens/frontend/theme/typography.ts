import type { TypographyVariantsOptions } from '@mui/material/styles';
import { fontTokens } from './tokens';

const { weight } = fontTokens;

export const typography: TypographyVariantsOptions = {
  fontFamily: fontTokens.family,
  h1: { fontWeight: weight.bold, fontSize: '2.5rem', lineHeight: 1.2 },
  h2: { fontWeight: weight.bold, fontSize: '2rem', lineHeight: 1.25 },
  h3: { fontWeight: weight.bold, fontSize: '1.75rem', lineHeight: 1.3 },
  h4: { fontWeight: weight.bold, fontSize: '1.5rem', lineHeight: 1.3 },
  h5: { fontWeight: weight.semibold, fontSize: '1.25rem', lineHeight: 1.35 },
  h6: { fontWeight: weight.semibold, fontSize: '1.0625rem', lineHeight: 1.4 },
  subtitle1: { fontWeight: weight.semibold, fontSize: '1rem', lineHeight: 1.5 },
  subtitle2: { fontWeight: weight.semibold, fontSize: '0.875rem', lineHeight: 1.5 },
  body1: { fontWeight: weight.regular, fontSize: '1rem', lineHeight: 1.6 },
  body2: { fontWeight: weight.regular, fontSize: '0.875rem', lineHeight: 1.6 },
  caption: { fontWeight: weight.medium, fontSize: '0.75rem', lineHeight: 1.5 },
  overline: {
    fontWeight: weight.bold,
    fontSize: '0.6875rem',
    letterSpacing: '0.08em',
    lineHeight: 1.5,
  },
  button: { fontWeight: weight.semibold, fontSize: '0.875rem', textTransform: 'none' },
};
