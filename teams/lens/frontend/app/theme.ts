import { alpha, createTheme } from '@mui/material/styles'

const fontStack =
  '-apple-system, BlinkMacSystemFont, "Segoe UI", Inter, Roboto, Helvetica, Arial, sans-serif'

declare module '@mui/material/styles' {
  interface Palette {
    sidebar: {
      background: string
      text: string
      textActive: string
      divider: string
      hover: string
    }
  }
  interface PaletteOptions {
    sidebar?: Palette['sidebar']
  }
}

export const theme = createTheme({
  palette: {
    mode: 'light',
    primary: {
      main: '#2563eb',
      dark: '#1d4ed8',
      light: '#3b82f6',
      contrastText: '#ffffff',
    },
    background: {
      default: '#ffffff',
      paper: '#ffffff',
    },
    text: {
      primary: '#0f172a',
      secondary: '#64748b',
    },
    divider: 'rgba(15, 23, 42, 0.08)',
    sidebar: {
      background: '#111827',
      text: alpha('#ffffff', 0.7),
      textActive: '#ffffff',
      divider: alpha('#ffffff', 0.08),
      hover: alpha('#ffffff', 0.06),
    },
  },
  shape: {
    borderRadius: 10,
  },
  typography: {
    fontFamily: fontStack,
    h4: { fontWeight: 700 },
    h6: { fontWeight: 700 },
    overline: { fontWeight: 700, letterSpacing: '0.08em' },
  },
  components: {
    MuiCssBaseline: {
      styleOverrides: {
        body: {
          backgroundColor: '#ffffff',
        },
      },
    },
    MuiAppBar: {
      styleOverrides: {
        root: {
          backgroundColor: '#ffffff',
          color: '#0f172a',
          borderBottom: '1px solid rgba(15, 23, 42, 0.08)',
        },
      },
    },
    MuiButton: {
      styleOverrides: {
        root: {
          textTransform: 'none',
          fontWeight: 600,
          borderRadius: 8,
        },
      },
    },
    MuiCard: {
      styleOverrides: {
        root: {
          border: '1px solid rgba(15, 23, 42, 0.07)',
          boxShadow: '0 1px 2px rgba(15, 23, 42, 0.04)',
        },
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
        root: {
          fontWeight: 600,
        },
      },
    },
  },
})
