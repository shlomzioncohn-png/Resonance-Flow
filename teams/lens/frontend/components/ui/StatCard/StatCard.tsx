'use client';

import Box from '@mui/material/Box';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Typography from '@mui/material/Typography';

export interface StatCardProps {
  /** The main label for this stat */
  label: string;
  /** The large value to display */
  value: string | number;
  /** Optional supporting text */
  subtitle?: string;
  /** Optional color theme: 'default' | 'success' | 'warning' | 'error' | 'info' */
  color?: 'default' | 'success' | 'warning' | 'error' | 'info';
}

/**
 * StatCard Component
 *
 * Simple card displaying a single statistic with label and value.
 * Used in dashboards for metrics like "Total Tasks", "In Progress", etc.
 */
export default function StatCard({ label, value, subtitle, color = 'default' }: StatCardProps) {
  const colorMap = {
    default: { bg: '#f5f5f5', text: '#333333' },
    success: { bg: '#e8f5e9', text: '#2e7d32' },
    warning: { bg: '#fff3e0', text: '#e65100' },
    error: { bg: '#ffebee', text: '#c62828' },
    info: { bg: '#e3f2fd', text: '#1565c0' },
  };

  const colors = colorMap[color];

  return (
    <Card sx={{ bgcolor: colors.bg, border: 'none' }}>
      <CardContent sx={{ textAlign: 'center' }}>
        <Typography variant="body2" color="textSecondary" sx={{ mb: 1 }}>
          {label}
        </Typography>
        <Typography
          variant="h4"
          sx={{
            fontWeight: 'bold',
            color: colors.text,
            mb: subtitle ? 1 : 0,
          }}
        >
          {value}
        </Typography>
        {subtitle && (
          <Typography variant="body2" color="textSecondary">
            {subtitle}
          </Typography>
        )}
      </CardContent>
    </Card>
  );
}
