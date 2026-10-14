CREATE TABLE unresolved_dependencies (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL,
    depends_on_task_id UUID NOT NULL,
    source VARCHAR(50) NOT NULL,
    source_ref VARCHAR(255) NOT NULL,
    reason VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL,

    CONSTRAINT uq_unresolved_dependency_pair
        UNIQUE (task_id, depends_on_task_id),

    CONSTRAINT uq_unresolved_dependency_source_ref
        UNIQUE (source, source_ref)
);

CREATE INDEX idx_unresolved_dependencies_target
    ON unresolved_dependencies(depends_on_task_id);

CREATE INDEX idx_unresolved_dependencies_task
    ON unresolved_dependencies(task_id);