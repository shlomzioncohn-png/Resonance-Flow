-- task.dependencies.proposed now creates PENDING placeholder tasks for missing tasks instead of
-- buffering the dependency, so nothing writes to this table anymore.
DROP TABLE IF EXISTS unresolved_dependencies;
