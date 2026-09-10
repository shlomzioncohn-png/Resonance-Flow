import type { Metadata } from 'next'
import type { ReactNode } from 'react'
import ThemeRegistry from './ThemeRegistry'
import { RoleProvider } from '@/components/layout/RoleProvider'
import { MockingProvider } from '@/components/layout/MockingProvider'
import './globals.css'

export const metadata: Metadata = {
  title: 'LENS',
  description: 'Resonance Flow LENS frontend',
}

export default function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <html lang="en">
      <body>
        {/* Outermost on purpose: nothing below should be able to fetch
            before MSW interception is confirmed active - see
            MockingProvider's own comment for why that ordering matters. */}
        <MockingProvider>
          <ThemeRegistry>
            <RoleProvider>{children}</RoleProvider>
          </ThemeRegistry>
        </MockingProvider>
      </body>
    </html>
  )
}
