/**
 * Admin Projects API
 * Client for calling the Gateway's admin projects endpoints
 */

import { api } from './api';

export interface AdminProjectDetails {
  projectId: string;
  projectName: string;
  description: string;
  status: 'healthy' | 'warning' | 'critical';
  startDate?: string;
  endDate?: string;
  totalTasks: number;
  inProgressTasks: number;
  blockedTasks: number;
  completedTasks: number;
  progressPercentage: number;
  processStages: Array<{ name: string; count: number }>;
  team: Array<{
    id: string;
    name: string;
    role: string;
    avatar?: string;
    activeTasks?: number;
    workload?: 'Available' | 'At Capacity' | 'Overloaded';
  }>;
  recentActivities: Array<{
    id: string;
    actor: { name: string; avatar?: string };
    action: string;
    description: string;
    timestamp: string;
    type?: 'success' | 'warning' | 'error' | 'info';
  }>;
}

/**
 * Fetch project details by ID
 * GET /api/admin/projects/{projectId}
 */
export function getProjectDetails(projectId: string): Promise<AdminProjectDetails> {
  return api.get<AdminProjectDetails>(`/api/admin/projects/${projectId}`);
}
