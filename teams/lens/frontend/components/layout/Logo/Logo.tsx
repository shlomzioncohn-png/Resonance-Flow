import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'

export interface LogoProps {
  variant?: 'light' | 'dark'
}

export default function Logo({ variant = 'dark' }: LogoProps) {
  const isDark = variant === 'dark'

  return (
    <Stack direction="row" spacing={1.25} sx={{ alignItems: 'center' }}>
      <Box
        sx={{
          width: 32,
          height: 32,
          borderRadius: '9px',
          bgcolor: 'primary.main',
          display: 'grid',
          placeItems: 'center',
          flexShrink: 0,
        }}
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
          <path
            d="M2 14c2.5 0 2.5-5 5-5s2.5 5 5 5 2.5-5 5-5 2.5 5 5 5"
            stroke="#ffffff"
            strokeWidth="2.4"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </Box>
      <Box sx={{ lineHeight: 1.1 }}>
        <Typography
          variant="subtitle1"
          component="span"
          sx={{ fontWeight: 700, color: isDark ? '#ffffff' : 'text.primary', display: 'block' }}
        >
          Resonance Flow
        </Typography>
        <Typography
          variant="caption"
          component="span"
          sx={{
            fontWeight: 700,
            letterSpacing: '0.08em',
            color: isDark ? 'rgba(255,255,255,0.55)' : 'text.secondary',
          }}
        >
          LENS
        </Typography>
      </Box>
    </Stack>
  )
}
