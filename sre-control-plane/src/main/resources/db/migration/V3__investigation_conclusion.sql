create table investigation_conclusion (
    incident_id varchar(40) primary key,
    root_cause varchar(80) not null,
    confidence numeric(5, 4) not null,
    evidence_ids jsonb not null,
    affected_payment_count bigint not null,
    affected_amount numeric(20, 4) not null,
    currency char(3) not null,
    recommended_runbook varchar(120) not null,
    requires_human_review boolean not null,
    created_at timestamp with time zone not null,
    constraint fk_conclusion_incident
        foreign key (incident_id) references incident (incident_id),
    constraint chk_conclusion_confidence
        check (confidence >= 0 and confidence <= 1),
    constraint chk_affected_payment_count
        check (affected_payment_count >= 0),
    constraint chk_affected_amount
        check (affected_amount >= 0)
);
