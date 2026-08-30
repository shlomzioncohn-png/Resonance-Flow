import type { Metadata } from 'next'
import type { ReactNode } from 'react'
import ThemeRegistry from './ThemeRegistry'
import './globals.css'

export const metadata: Metadata = {
  title: 'LENS',
  description: 'Resonance Flow LENS frontend',
}

export default function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <html lang="en">
      <body>
        <ThemeRegistry>{children}</ThemeRegistry>
      </body>
    </html>
  )
}
