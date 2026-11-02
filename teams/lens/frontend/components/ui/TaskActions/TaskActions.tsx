'use client';

import { useState, type ReactNode } from 'react';
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Divider from '@mui/material/Divider';
import ListItemIcon from '@mui/material/ListItemIcon';
import ListItemText from '@mui/material/ListItemText';
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import ArrowForwardOutlinedIcon from '@mui/icons-material/ArrowForwardOutlined';
import BlockOutlinedIcon from '@mui/icons-material/BlockOutlined';
import CheckCircleOutlinedIcon from '@mui/icons-material/CheckCircleOutlined';
import KeyboardArrowDownOutlinedIcon from '@mui/icons-material/KeyboardArrowDownOutlined';
import PlayArrowOutlinedIcon from '@mui/icons-material/PlayArrowOutlined';

/**
 * Why a task can't be worked on right now - the "Can't do this now" list
 * (teams/lens/docs/employee-workspace/employee-ui-design.md §6, teams/lens/docs/employee-workspace/employee-workspace-api-contract.md §4.4).
 */
export type BlockReason =
  | 'WAITING_FOR_MANAGER_APPROVAL'
  | 'BLOCKED_BY_ANOTHER_TASK'
  | 'WAITING_FOR_CUSTOMER'
  | 'WAITING_FOR_INFORMATION'
  | 'OTHER';

export const BLOCK_REASON_LABELS: Record<BlockReason, string> = {
  WAITING_FOR_MANAGER_APPROVAL: 'Waiting for manager approval',
  BLOCKED_BY_ANOTHER_TASK: 'Blocked by another task',
  WAITING_FOR_CUSTOMER: 'Waiting for customer',
  WAITING_FOR_INFORMATION: 'Missing information',
  OTHER: 'Other reason',
};

/**
 * Why the employee rejects a task. Inbox saves only a free text
 * (feedback_text) and has no category field, so the page puts the category
 * at the start of that text.
 */
export type RejectCategory =
  'NOT_MY_RESPONSIBILITY' | 'DUPLICATE' | 'ALREADY_DONE' | 'NO_LONGER_RELEVANT' | 'OTHER';

export const REJECT_CATEGORY_LABELS: Record<RejectCategory, string> = {
  NOT_MY_RESPONSIBILITY: 'Not my responsibility',
  DUPLICATE: 'Duplicate of another task',
  ALREADY_DONE: 'Already done elsewhere',
  NO_LONGER_RELEVANT: 'No longer relevant',
  OTHER: 'Other',
};

export interface BlockDetails {
  reason: BlockReason;
  /** Free text - asked for "Other reason" and "Blocked by another task". */
  note?: string;
}

export interface RejectDetails {
  category: RejectCategory;
  /** Free text, required. */
  reason: string;
}

export interface TaskActionsProps {
  /** One of the 8 Inbox statuses - decides the main button. */
  status: string;
  /** Link for "View details". */
  detailsHref?: string;
  onStart?: () => void;
  onDone?: () => void;
  onBlock?: (details: BlockDetails) => void;
  onReject?: (details: RejectDetails) => void;
  /** Shows a spinner on the main button and disables all actions. */
  busy?: boolean;
  /** Renders the details link (e.g. a Next.js Link) - defaults to a plain anchor. */
  linkComponent?: React.ElementType;
}

/** Reasons that need a few words from the employee before they're sent. */
const REASONS_WITH_NOTE: BlockReason[] = ['BLOCKED_BY_ANOTHER_TASK', 'OTHER'];

/**
 * The actions on a recommended task, replacing the dropped chat:
 * one main button that follows the status (Start task / Mark as done), and
 * every "I can't do it" case folded into a single "Can't do this now" menu so
 * the card doesn't fill up with buttons. Rejecting is the last menu item and
 * asks for a category and a reason (Inbox needs both).
 *
 * Presentational only: it reports what was chosen through the callbacks and
 * never calls the API itself.
 */
export default function TaskActions({
  status,
  detailsHref,
  onStart,
  onDone,
  onBlock,
  onReject,
  busy = false,
  linkComponent = 'a',
}: TaskActionsProps) {
  const [menuAnchor, setMenuAnchor] = useState<HTMLElement | null>(null);
  const [noteReason, setNoteReason] = useState<BlockReason | null>(null);
  const [note, setNote] = useState('');
  const [rejectOpen, setRejectOpen] = useState(false);
  const [rejectCategory, setRejectCategory] = useState<RejectCategory | ''>('');
  const [rejectReason, setRejectReason] = useState('');

  const upper = status.toUpperCase();
  const isClosed = ['DONE', 'REJECTED', 'CANCELLED'].includes(upper);
  const inProgress = upper === 'IN_PROGRESS';

  const chooseReason = (reason: BlockReason) => {
    setMenuAnchor(null);
    if (REASONS_WITH_NOTE.includes(reason)) {
      setNote('');
      setNoteReason(reason);
    } else {
      onBlock?.({ reason });
    }
  };

  const openReject = () => {
    setMenuAnchor(null);
    setRejectCategory('');
    setRejectReason('');
    setRejectOpen(true);
  };

  const mainButton: ReactNode = inProgress ? (
    <Button
      variant="contained"
      color="success"
      disabled={busy}
      onClick={onDone}
      startIcon={
        busy ? <CircularProgress size={16} color="inherit" aria-hidden /> : <CheckCircleOutlinedIcon />
      }
      data-testid="done-task-btn"
    >
      Mark as done
    </Button>
  ) : (
    <Button
      variant="contained"
      disabled={busy}
      onClick={onStart}
      startIcon={busy ? <CircularProgress size={16} color="inherit" aria-hidden /> : <PlayArrowOutlinedIcon />}
      data-testid="start-task-btn"
    >
      Start task
    </Button>
  );

  return (
    <>
      <Stack direction="row" spacing={1.5} sx={{ flexWrap: 'wrap', rowGap: 1.5 }}>
        {!isClosed && mainButton}
        {!isClosed && !inProgress && (
          <Button
            variant="outlined"
            disabled={busy}
            onClick={onDone}
            startIcon={<CheckCircleOutlinedIcon />}
            data-testid="done-task-btn"
          >
            Mark as done
          </Button>
        )}
        {!isClosed && (
          <Button
            variant="outlined"
            color="warning"
            disabled={busy}
            onClick={(event) => setMenuAnchor(event.currentTarget)}
            endIcon={<KeyboardArrowDownOutlinedIcon />}
            aria-haspopup="menu"
            aria-expanded={Boolean(menuAnchor)}
            data-testid="cant-do-btn"
          >
            Can&apos;t do this now
          </Button>
        )}
        {detailsHref && (
          <Button
            variant="text"
            component={linkComponent}
            href={detailsHref}
            endIcon={<ArrowForwardOutlinedIcon />}
          >
            View details
          </Button>
        )}
      </Stack>

      <Menu anchorEl={menuAnchor} open={Boolean(menuAnchor)} onClose={() => setMenuAnchor(null)}>
        {(Object.keys(BLOCK_REASON_LABELS) as BlockReason[]).map((reason) => (
          <MenuItem key={reason} onClick={() => chooseReason(reason)}>
            {BLOCK_REASON_LABELS[reason]}
            {REASONS_WITH_NOTE.includes(reason) && '…'}
          </MenuItem>
        ))}
        <Divider />
        <MenuItem onClick={openReject} sx={{ color: 'error.main' }}>
          <ListItemIcon sx={{ color: 'error.main' }}>
            <BlockOutlinedIcon fontSize="small" />
          </ListItemIcon>
          <ListItemText>Reject task…</ListItemText>
        </MenuItem>
      </Menu>

      {/* A few words for "Blocked by another task" / "Other reason" */}
      <Dialog
        open={noteReason !== null}
        onClose={() => setNoteReason(null)}
        fullWidth
        maxWidth="xs"
      >
        <DialogTitle>{noteReason && BLOCK_REASON_LABELS[noteReason]}</DialogTitle>
        <DialogContent>
          <TextField
            fullWidth
            multiline
            minRows={2}
            margin="dense"
            label={
              noteReason === 'BLOCKED_BY_ANOTHER_TASK'
                ? 'Which task is blocking it?'
                : 'What is blocking it?'
            }
            value={note}
            onChange={(event) => setNote(event.target.value)}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setNoteReason(null)}>Cancel</Button>
          <Button
            variant="contained"
            disabled={!note.trim()}
            onClick={() => {
              if (noteReason) onBlock?.({ reason: noteReason, note: note.trim() });
              setNoteReason(null);
            }}
          >
            Save
          </Button>
        </DialogActions>
      </Dialog>

      {/* Reject: category + free-text reason, both required (Inbox's request) */}
      <Dialog open={rejectOpen} onClose={() => setRejectOpen(false)} fullWidth maxWidth="xs">
        <DialogTitle>Reject this task</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            <TextField
              select
              fullWidth
              label="Why are you rejecting it?"
              value={rejectCategory}
              onChange={(event) => setRejectCategory(event.target.value as RejectCategory)}
              data-testid="reject-category"
            >
              {(Object.keys(REJECT_CATEGORY_LABELS) as RejectCategory[]).map((category) => (
                <MenuItem key={category} value={category}>
                  {REJECT_CATEGORY_LABELS[category]}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              fullWidth
              multiline
              minRows={3}
              label="Tell us more"
              placeholder="A few words on why this task isn't for you"
              value={rejectReason}
              onChange={(event) => setRejectReason(event.target.value)}
              data-testid="reject-reason"
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRejectOpen(false)}>Cancel</Button>
          <Button
            variant="contained"
            color="error"
            disabled={!rejectCategory || !rejectReason.trim()}
            onClick={() => {
              if (rejectCategory)
                onReject?.({ category: rejectCategory, reason: rejectReason.trim() });
              setRejectOpen(false);
            }}
            data-testid="reject-confirm"
          >
            Reject task
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
