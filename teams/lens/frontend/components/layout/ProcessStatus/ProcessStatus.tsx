'use client'

import Box from '@mui/material/Box'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Typography from '@mui/material/Typography'
import Stack from '@mui/material/Stack'

export interface ProcessStage {
  name: string
  count: number
  color?: string
}

export interface ProcessStatusProps {
  stages: ProcessStage[]
  /** Optional title for the section */
  title?: string
}

/**
 * ProcessStatus Component
 *
 * Displays a visual representation of workflow stages with task counts.
 * Shows stages like Planning, In Progress, Review, Testing, Done.
 */
export default function ProcessStatus({
  stages,
  title = 'Process Status',
}: ProcessStatusProps) {
  const defaultColors = [
    '#f3f4f6', // Planning - gray
    '#3b82f6', // In Progress - blue
    '#f97316', // Review - orange
    '#a855f7', // Testing - purple
    '#10b981', // Done - green
  ]

  return (
    <Card>
      <CardContent>
        <Typography variant="h6" sx={{ mb: 3 }}>
          {title}
        </Typography>
        <Box sx={{ display: 'flex', gap: 1.5, overflowX: 'auto', pb: 1 }}>
          {stages.map((stage, index) => {
            const bgColor = stage.color || defaultColors[index % defaultColors.length]
            const isLight = index === 0

            return (
              <Card
                key={stage.name}
                variant="outlined"
                sx={{
                  bgcolor: isLight ? bgColor : `${bgColor}15`,
                  border: `2px solid ${bgColor}`,
                  textAlign: 'center',
                  minWidth: '140px',
                  flex: '0 0 140px',
                }}
              >
                <CardContent sx={{ py: 2, '&:last-child': { pb: 2 } }}>
                  <Typography
                    variant="caption"
                    sx={{
                      display: 'block',
                      textTransform: 'uppercase',
                      fontSize: '0.7rem',
                      fontWeight: 600,
                      color: isLight ? '#666' : bgColor,
                      mb: 1,
                    }}
                  >
                    {stage.name}
                    {index === 1 && (
                      <Box
                        component="span"
                        sx={{
                          display: 'inline-block',
                          ml: 0.5,
                          px: 0.75,
                          py: 0.25,
                          bgcolor: bgColor,
                          color: 'white',
                          borderRadius: 0.5,
                          fontSize: '0.65rem',
                          fontWeight: 700,
                        }}
                      >
                        ACTIVE
                      </Box>
                    )}
                  </Typography>
                  <Typography
                    variant="h4"
                    sx={{
                      color: isLight ? '#000' : bgColor,
                      fontWeight: 'bold',
                      mb: 0.5,
                    }}
                  >
                    {stage.count}
                  </Typography>
                  <Typography
                    variant="caption"
                    sx={{
                      display: 'block',
                      color: isLight ? '#999' : '#999',
                      fontSize: '0.75rem',
                    }}
                  >
                    tasks
                  </Typography>
                </CardContent>
              </Card>
            )
          })}
        </Box>
      </CardContent>
    </Card>
  )
}
