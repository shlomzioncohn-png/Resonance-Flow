'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { useRole } from '@/components/layout/RoleProvider';
import { homeFor } from '@/components/layout/Sidebar/navConfig';

// "/" has no screen of its own - it sends each role to its home screen.
// AppShell has already sent signed-out users to /login.
export default function HomePage() {
  const { role } = useRole();
  const router = useRouter();

  useEffect(() => {
    if (role) router.replace(homeFor(role));
  }, [role, router]);

  return null;
}
