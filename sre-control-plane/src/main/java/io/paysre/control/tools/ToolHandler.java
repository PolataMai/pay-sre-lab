package io.paysre.control.tools;

public interface ToolHandler<I, O> {

    ToolDefinition definition();

    Class<I> inputType();

    O execute(I input);
}
