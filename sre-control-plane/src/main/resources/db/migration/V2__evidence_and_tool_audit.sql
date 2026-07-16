create table evidence (
    evidence_id varchar(40) primary key,
    incident_id varchar(40) not null,
    evidence_type varchar(64) not null,
    source_tool varchar(80) not null,
    source_tool_version integer not null,
    content jsonb not null,
    sha256 char(64) not null,
    collected_at timestamp with time zone not null,
    constraint fk_evidence_incident
        foreign key (incident_id) references incident (incident_id)
);

create index idx_evidence_incident
    on evidence (incident_id, collected_at);

create table tool_invocation (
    invocation_id varchar(40) primary key,
    incident_id varchar(40) not null,
    agent_id varchar(80) not null,
    tool_name varchar(80) not null,
    tool_version integer not null,
    successful boolean not null,
    evidence_ids jsonb not null,
    duration_millis bigint not null,
    error_code varchar(64),
    invoked_at timestamp with time zone not null,
    constraint fk_tool_invocation_incident
        foreign key (incident_id) references incident (incident_id)
);

create index idx_tool_invocation_incident
    on tool_invocation (incident_id, invoked_at);
