package com.devgraphx.core.model;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class GraphBuilder {
    private final Map<String, CodeNode> nodes = new LinkedHashMap<>();
    private final Set<CodeEdge> edges = new LinkedHashSet<>();
    private int unresolvedMethodCallCount;

    public void node(CodeNode node) {
        nodes.putIfAbsent(node.id(), node);
    }

    public void edge(CodeEdge edge) {
        if (!edge.source().equals(edge.target())) {
            edges.add(edge);
        }
    }

    public boolean hasNode(String id) {
        return nodes.containsKey(id);
    }

    public void unresolvedMethodCall() {
        unresolvedMethodCallCount++;
    }

    public CodeGraph build() {
        CodeGraph graph = new CodeGraph();
        graph.setNodes(nodes.values().stream().toList());
        graph.setEdges(edges.stream().toList());
        graph.setUnresolvedMethodCallCount(unresolvedMethodCallCount);
        return graph;
    }
}
