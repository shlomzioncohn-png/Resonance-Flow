'use client'

import { useEffect, useState, useCallback } from 'react'
import { useParams } from 'next/navigation'
import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import Typography from '@mui/material/Typography'
import Alert from '@mui/material/Alert'
import AdminProjectDetails from '@/components/layout/AdminProjectDetails/AdminProjectDetails'
import { getProjectDetails, type AdminProjectDetails as AdminProjectDetailsType } from '@/services/adminProjectsApi'

type LoadState = 'loading' | 'loaded' | 'error'

/**
 * Project Details Page
 * 
 * Displays full project details for manager view
 * Fetches from /api/admin/projects/{projectId}
 */
export default function AdminProjectDetailsPage() {
  const params = useParams()
  const projectId = params.projectId as string
  
  const [state, setState] = useState<LoadState>('loading')
  const [project, setProject] = useState<AdminProjectDetailsType | null>(null)
  const [error, setError] = useState<string | null>(null)

  const fetchProject = useCallback(() => {
    setState('loading')
    setError(null)
    
    getProjectDetails(projectId)
      .then((data) => {
        setProject(data)
        setState('loaded')
      })
      .catch((err) => {
        const message = err instanceof Error ? err.message : 'Failed to load project'
        setError(message)
        setState('error')
      })
  }, [projectId])

  useEffect(() => {
    fetchProject()
  }, [fetchProject])

  const handleRetry = () => {
    fetchProject()
  }

  const handleEdit = () => {
    console.log('Edit project:', projectId)
    // TODO: Implement edit functionality
  }

  const handleArchive = () => {
    console.log('Archive project:', projectId)
    // TODO: Implement archive functionality
  }

  const handleDelete = () => {
    if (window.confirm('Are you sure you want to delete this project?')) {
      console.log('Delete project:', projectId)
      // TODO: Implement delete functionality
    }
  }

  if (state === 'loading') {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '400px' }}>
        <CircularProgress />
      </Box>
    )
  }

  if (state === 'error') {
    return (
      <Box sx={{ p: 3 }}>
        <Alert severity="error" onClose={handleRetry}>
          {error}
        </Alert>
      </Box>
    )
  }

  if (!project) {
    return (
      <Box sx={{ p: 3 }}>
        <Typography color="textSecondary">Project not found</Typography>
      </Box>
    )
  }

  return (
    <AdminProjectDetails
      projectId={project.projectId}
      projectName={project.projectName}
      description={project.description}
      status={project.status}
      startDate={project.startDate}
      endDate={project.endDate}
      totalTasks={project.totalTasks}
      inProgressTasks={project.inProgressTasks}
      blockedTasks={project.blockedTasks}
      completedTasks={project.completedTasks}
      progressPercentage={project.progressPercentage}
      processStages={project.processStages}
      team={project.team}
      recentActivities={project.recentActivities}
      onEdit={handleEdit}
      onArchive={handleArchive}
      onDelete={handleDelete}
    />
  )
}
