-- Feedback is now free text only, so a row without text carries nothing and is removed before the constraint.
ALTER TABLE task_feedback DROP COLUMN feedback_type;
DELETE FROM task_feedback WHERE feedback_text IS NULL;
ALTER TABLE task_feedback ALTER COLUMN feedback_text SET NOT NULL;
