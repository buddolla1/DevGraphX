package com.devgraphx.core.model;

public record CodeEdge(
        String source,
        String target,
        String relation,
        String sourceFile,
        int line
) {
}
