package io.paysre.control.tools;

public interface ToolAuditRepository {

    void record(ToolInvocation invocation);
}
