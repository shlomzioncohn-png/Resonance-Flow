import { Typography } from '@mui/material'

export default function HomePage() {
  return (
    <main className="app-shell">
      <Typography variant="overline" component="p">Resonance Flow</Typography>
      <Typography variant="h1" component="h1">LENS is running</Typography>
      <Typography variant="body1" component="p">
        Frontend foundation is ready for the next slice.
      </Typography>
    </main>
  )
}