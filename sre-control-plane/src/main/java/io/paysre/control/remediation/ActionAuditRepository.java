package io.paysre.control.remediation;

import java.util.List;

public interface ActionAuditRepository {

    void record(ActionInvocation invocation);

    List<ActionInvocation> findByIncidentId(String incidentId);
}
