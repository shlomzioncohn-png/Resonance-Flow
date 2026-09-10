CREATE UNIQUE INDEX uq_tasks_org_source_type_source_ref
    ON tasks(organization_id, source_type, source_ref)
    WHERE source_ref IS NOT NULL;
