'use client';

import { useState, type MouseEvent } from 'react';
import Chip from '@mui/material/Chip';
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import ArrowDropDownIcon from '@mui/icons-material/ArrowDropDown';

export interface FilterChipOption {
  label: string;
  value: string;
}

export interface FilterChipProps {
  /** Prefix shown before the selected option's label, e.g. "Status" → "Status: All". */
  label: string;
  options: FilterChipOption[];
  value: string;
  onChange: (value: string) => void;
}

/**
 * A single clickable Chip (not a Chip + separate delete-icon button) so the
 * whole control is one focusable, keyboard-operable trigger for the dropdown
 * menu — MUI's `deleteIcon` slot would add a second focus stop with a
 * misleading default "delete" label for this non-deleting use.
 */
export default function FilterChip({ label, options, value, onChange }: FilterChipProps) {
  const [anchorEl, setAnchorEl] = useState<HTMLElement | null>(null);
  const selected = options.find((option) => option.value === value);

  const handleOpen = (event: MouseEvent<HTMLElement>) => setAnchorEl(event.currentTarget);
  const handleClose = () => setAnchorEl(null);
  const handleSelect = (optionValue: string) => {
    onChange(optionValue);
    handleClose();
  };

  return (
    <>
      <Chip
        variant="outlined"
        clickable
        onClick={handleOpen}
        aria-haspopup="listbox"
        aria-expanded={Boolean(anchorEl)}
        label={
          <Stack direction="row" spacing={0.25} sx={{ alignItems: 'center' }}>
            <span>{`${label}: ${selected?.label ?? value}`}</span>
            <ArrowDropDownIcon fontSize="small" />
          </Stack>
        }
      />
      <Menu anchorEl={anchorEl} open={Boolean(anchorEl)} onClose={handleClose}>
        {options.map((option) => (
          <MenuItem
            key={option.value}
            selected={option.value === value}
            onClick={() => handleSelect(option.value)}
          >
            {option.label}
          </MenuItem>
        ))}
      </Menu>
    </>
  );
}
