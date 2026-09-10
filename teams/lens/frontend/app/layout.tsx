import type { Metadata } from 'next'
import type { ReactNode } from 'react'
import ThemeRegistry from './ThemeRegistry'
import { RoleProvider } from '@/components/layout/RoleProvider'
import './globals.css'

export const metadata: Metadata = {
  title: 'LENS',
  description: 'Resonance Flow LENS frontend',
}

export default function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <html lang="en">
      <body>
        <ThemeRegistry>
          <RoleProvider>{children}</RoleProvider>
        </ThemeRegistry>
      </body>
    </html>
  )
}
