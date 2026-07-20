create table runbook_execution (
    execution_id varchar(60) primary key,
    incident_id varchar(40) not null,
    runbook varchar(120) not null,
    status varchar(30) not null,
    requested_by varchar(120) not null,
    approved_by varchar(120),
    result jsonb,
    error varchar(1000),
    version bigint not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint fk_runbook_execution_incident
        foreign key (incident_id) references incident (incident_id)
);

create index idx_runbook_execution_incident
    on runbook_execution (incident_id);

create table action_audit (
    audit_id varchar(60) primary key,
    incident_id varchar(40) not null,
    execution_id varchar(60),
    action varchar(60) not null,
    actor varchar(120) not null,
    allowed boolean not null,
    reason_code varchar(120),
    occurred_at timestamp with time zone not null
);

create index idx_action_audit_incident
    on action_audit (incident_id);
