import type { ReactNode } from 'react';
import AppShell from '@/components/layout/AppShell';
import { QueryProvider } from '@/components/providers/QueryProvider';

export default function AppGroupLayout({ children }: { children: ReactNode }) {
  return (
    <QueryProvider>
      <AppShell>{children}</AppShell>
    </QueryProvider>
  );
}
