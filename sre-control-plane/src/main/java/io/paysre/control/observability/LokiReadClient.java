package io.paysre.control.observability;

@FunctionalInterface
public interface LokiReadClient {

    StructuredLogResult search(StructuredLogSearch search);
}
