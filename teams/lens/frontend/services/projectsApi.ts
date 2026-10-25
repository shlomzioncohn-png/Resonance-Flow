/**
 * Manager API — typed wrapper around the all-projects list with health/KPI
 * data. Real data: the BFF counts the tenant's Inbox tasks per project
 * (ManagerKpisService.getProjects). Team, owner and members are placeholders
 * ("No team" / "Unassigned" / none) until Inbox's project API is available.
 * Distinct from services/adminProjectsApi.ts, which backs the separate
 * per-project drill-down page.
 */
import { api } from './api';
import type { StatusTone } from '@/components/ui/StatusBadge';

export interface ProjectMember {
  name: string;
  avatarUrl?: string;
}

export interface Project {
  id: string;
  name: string;
  team: string;
  owner: string;
  status: StatusTone;
  statusLabel: string;
  members: ProjectMember[];
  /** 0-100 */
  progressPercent: number;
  totalCount: number;
  inProgressCount: number;
  blockedCount: number;
  lastUpdatedAt: string;
}

/** Get all projects with health/KPI data (mocked at the BFF, see above). */
export function getProjects(): Promise<Project[]> {
  return api.get<Project[]>('/api/manager/projects');
}
