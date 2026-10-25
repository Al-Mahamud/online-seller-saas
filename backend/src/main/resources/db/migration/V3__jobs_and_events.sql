-- Platform tables, not tenant data: no business_id and no row-level security.

-- db-scheduler 16.x (backend-plan.md §10), copied from its postgresql_tables.sql.
CREATE TABLE scheduled_tasks (
    task_name            text                     NOT NULL,
    task_instance        text                     NOT NULL,
    task_data            bytea,
    execution_time       timestamp with time zone NOT NULL,
    picked               boolean                  NOT NULL,
    picked_by            text,
    last_success         timestamp with time zone,
    last_failure         timestamp with time zone,
    consecutive_failures int,
    last_heartbeat       timestamp with time zone,
    version              bigint                   NOT NULL,
    priority             smallint,
    PRIMARY KEY (task_name, task_instance)
);
CREATE INDEX execution_time_idx ON scheduled_tasks (execution_time);
CREATE INDEX last_heartbeat_idx ON scheduled_tasks (last_heartbeat);
CREATE INDEX priority_execution_time_idx ON scheduled_tasks (priority DESC, execution_time ASC);

-- Spring Modulith 2.x event publication registry, copied from its v2 schema-postgresql.sql.
-- An event is stored before its listeners run and marked complete after; incomplete ones are
-- retried on restart.
CREATE TABLE event_publication (
    id                     uuid                     NOT NULL,
    listener_id            text                     NOT NULL,
    event_type             text                     NOT NULL,
    serialized_event       text                     NOT NULL,
    publication_date       timestamp with time zone NOT NULL,
    completion_date        timestamp with time zone,
    status                 text,
    completion_attempts    int,
    last_resubmission_date timestamp with time zone,
    PRIMARY KEY (id)
);
CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);
