package io.paysre.control.tools;

import java.util.List;

public interface ToolAuditRepository {

    void record(ToolInvocation invocation);

    List<ToolInvocation> findByIncidentId(String incidentId);
}
