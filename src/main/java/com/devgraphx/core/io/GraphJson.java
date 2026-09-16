package com.devgraphx.core.io;

import com.devgraphx.core.model.CodeGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GraphJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private GraphJson() {
    }

    public static void write(CodeGraph graph, Path output) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        MAPPER.writeValue(output.toFile(), graph);
    }

    public static CodeGraph read(Path input) throws IOException {
        return MAPPER.readValue(input.toFile(), CodeGraph.class);
    }
}
