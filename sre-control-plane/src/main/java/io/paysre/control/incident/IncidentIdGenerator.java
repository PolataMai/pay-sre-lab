package io.paysre.control.incident;

@FunctionalInterface
public interface IncidentIdGenerator {

    String nextIncidentId();
}
