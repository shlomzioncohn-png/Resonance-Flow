-- When Mind computed the current score of the task (task.score.proposed scored_at); an event with an
-- earlier scored_at than this one is ignored.
ALTER TABLE task
    ADD COLUMN scored_at TIMESTAMP;
