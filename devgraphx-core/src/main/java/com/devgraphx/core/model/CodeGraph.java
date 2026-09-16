package com.devgraphx.core.model;

import java.util.ArrayList;
import java.util.List;

public final class CodeGraph {
    private List<CodeNode> nodes = new ArrayList<>();
    private List<CodeEdge> edges = new ArrayList<>();
    private int unresolvedMethodCallCount;

    public List<CodeNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<CodeNode> nodes) {
        this.nodes = nodes;
    }

    public List<CodeEdge> getEdges() {
        return edges;
    }

    public void setEdges(List<CodeEdge> edges) {
        this.edges = edges;
    }

    public int getUnresolvedMethodCallCount() {
        return unresolvedMethodCallCount;
    }

    public void setUnresolvedMethodCallCount(int unresolvedMethodCallCount) {
        this.unresolvedMethodCallCount = unresolvedMethodCallCount;
    }
}
