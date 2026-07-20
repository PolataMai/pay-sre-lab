package io.paysre.control.observability;

@FunctionalInterface
public interface TempoReadClient {

    DistributedTrace get(DistributedTraceQuery query);
}
