package com.devgraphx.core.query;

import com.devgraphx.core.model.CodeEdge;
import com.devgraphx.core.model.CodeGraph;
import com.devgraphx.core.model.CodeNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

public final class GraphIndex {
    private final CodeGraph graph;
    private final Map<String, CodeNode> byId = new HashMap<>();
    private final Map<String, List<CodeEdge>> outgoing = new HashMap<>();
    private final Map<String, List<CodeEdge>> incoming = new HashMap<>();

    public GraphIndex(CodeGraph graph) {
        this.graph = graph;
        for (CodeNode node : graph.getNodes()) {
            byId.put(node.id(), node);
        }
        for (CodeEdge edge : graph.getEdges()) {
            outgoing.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge);
            incoming.computeIfAbsent(edge.target(), ignored -> new ArrayList<>()).add(edge);
        }
    }

    public Optional<CodeNode> findNode(String query) {
        if (byId.containsKey(query)) {
            return Optional.of(byId.get(query));
        }
        String lowered = query.toLowerCase(Locale.ROOT);
        return graph.getNodes().stream()
                .filter(node -> node.label().equals(query))
                .findFirst()
                .or(() -> findMethod(query, lowered))
                .or(() -> graph.getNodes().stream()
                        .filter(node -> node.id().equalsIgnoreCase(query)
                                || node.label().equalsIgnoreCase(query))
                        .findFirst())
                .or(() -> graph.getNodes().stream()
                        .filter(node -> node.id().toLowerCase(Locale.ROOT).contains(lowered)
                                || node.label().toLowerCase(Locale.ROOT).contains(lowered))
                        .min(Comparator.comparingInt(node -> node.id().length())));
    }

    public ExplainResult explain(String query) {
        CodeNode node = findNode(query).orElseThrow(() -> new IllegalArgumentException("No node matching " + query));
        List<CodeEdge> edges = new ArrayList<>();
        edges.addAll(incoming.getOrDefault(node.id(), List.of()).stream()
                .filter(edge -> !isMethod(node) || !"called-by".equals(edge.relation()))
                .toList());
        edges.addAll(outgoing.getOrDefault(node.id(), List.of()));
        return new ExplainResult(node, rankEdges(node.id(), edges));
    }

    public List<CodeNode> affected(String query) {
        CodeNode node = findNode(query).orElseThrow(() -> new IllegalArgumentException("No node matching " + query));
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        List<CodeNode> result = new ArrayList<>();
        seen.add(node.id());
        List<String> seeds = affectedSeeds(node);
        for (String seed : seeds) {
            queue.add(seed);
            seen.add(seed);
            if (!seed.equals(node.id())) {
                CodeNode seedNode = byId.get(seed);
                if (seedNode != null) {
                    result.add(seedNode);
                }
            }
        }

        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (CodeEdge edge : impactEdges(current)) {
                String next = nextImpactNode(current, edge);
                if (next != null && seen.add(next)) {
                    CodeNode nextNode = byId.get(next);
                    if (nextNode != null) {
                        result.add(nextNode);
                        queue.add(nextNode.id());
                    }
                }
            }
        }
        return result.stream()
                .sorted(Comparator.comparingInt(this::nodeRank).thenComparing(CodeNode::id))
                .toList();
    }

    public List<CodeNode> path(String fromQuery, String toQuery) {
        CodeNode from = findNode(fromQuery).orElseThrow(() -> new IllegalArgumentException("No node matching " + fromQuery));
        CodeNode to = findNode(toQuery).orElseThrow(() -> new IllegalArgumentException("No node matching " + toQuery));

        Map<String, String> previous = new HashMap<>();
        Map<String, Integer> distance = new HashMap<>();
        PriorityQueue<PathStep> queue = new PriorityQueue<>(Comparator
                .comparingInt(PathStep::cost)
                .thenComparing(PathStep::nodeId));
        distance.put(from.id(), 0);
        queue.add(new PathStep(from.id(), 0));

        while (!queue.isEmpty()) {
            PathStep step = queue.remove();
            String current = step.nodeId();
            if (step.cost() > distance.getOrDefault(current, Integer.MAX_VALUE)) {
                continue;
            }
            if (current.equals(to.id())) {
                return reconstruct(previous, to.id());
            }
            for (CodeEdge edge : rankEdges(current, adjacent(current))) {
                String next = edge.source().equals(current) ? edge.target() : edge.source();
                int cost = step.cost() + pathWeight(current, edge, next);
                if (cost < distance.getOrDefault(next, Integer.MAX_VALUE)) {
                    distance.put(next, cost);
                    previous.put(next, current);
                    queue.add(new PathStep(next, cost));
                }
            }
        }
        return List.of();
    }

    private List<CodeEdge> adjacent(String nodeId) {
        List<CodeEdge> edges = new ArrayList<>();
        edges.addAll(outgoing.getOrDefault(nodeId, List.of()));
        edges.addAll(incoming.getOrDefault(nodeId, List.of()));
        return edges;
    }

    public List<CodeEdge> incoming(String nodeId) {
        return rankEdges(nodeId, incoming.getOrDefault(nodeId, List.of()));
    }

    public List<CodeEdge> outgoing(String nodeId) {
        return rankEdges(nodeId, outgoing.getOrDefault(nodeId, List.of()));
    }

    public Optional<CodeNode> node(String nodeId) {
        return Optional.ofNullable(byId.get(nodeId));
    }

    public List<CodeNode> methodsOwnedBy(CodeNode node) {
        if (!isType(node)) {
            return List.of();
        }
        return outgoing.getOrDefault(node.id(), List.of()).stream()
                .filter(edge -> "contains".equals(edge.relation()))
                .map(edge -> byId.get(edge.target()))
                .filter(this::isMethod)
                .sorted(Comparator.comparing(CodeNode::id))
                .toList();
    }

    private Optional<CodeNode> findMethod(String query, String lowered) {
        String normalized = query.replace('#', '.').toLowerCase(Locale.ROOT);
        return graph.getNodes().stream()
                .filter(this::isMethod)
                .filter(node -> node.signature().equals(query)
                        || node.id().equalsIgnoreCase(query)
                        || node.label().equalsIgnoreCase(query))
                .findFirst()
                .or(() -> graph.getNodes().stream()
                        .filter(this::isMethod)
                        .filter(node -> node.id().toLowerCase(Locale.ROOT).contains(normalized)
                                || node.label().toLowerCase(Locale.ROOT).contains(lowered))
                        .min(Comparator.comparingInt(node -> node.id().length())));
    }

    private List<String> affectedSeeds(CodeNode node) {
        if (!isType(node)) {
            return List.of(node.id());
        }
        List<String> methodIds = methodsOwnedBy(node).stream()
                .map(CodeNode::id)
                .toList();
        if (methodIds.isEmpty()) {
            return List.of(node.id());
        }
        List<String> seeds = new ArrayList<>(methodIds);
        seeds.add(node.id());
        return seeds;
    }

    private List<CodeEdge> impactEdges(String nodeId) {
        CodeNode node = byId.get(nodeId);
        List<CodeEdge> edges = new ArrayList<>();
        if (isMethod(node)) {
            edges.addAll(incoming.getOrDefault(nodeId, List.of()).stream()
                    .filter(edge -> "calls".equals(edge.relation()))
                    .toList());
            edges.addAll(outgoing.getOrDefault(nodeId, List.of()).stream()
                    .filter(edge -> "called-by".equals(edge.relation()))
                    .toList());
        } else {
            edges.addAll(incoming.getOrDefault(nodeId, List.of()).stream()
                    .filter(edge -> "references".equals(edge.relation())
                            || "imports".equals(edge.relation())
                            || "extends".equals(edge.relation())
                            || "implements".equals(edge.relation()))
                    .toList());
        }
        return rankEdges(nodeId, edges);
    }

    private String nextImpactNode(String current, CodeEdge edge) {
        if ("called-by".equals(edge.relation()) && edge.source().equals(current)) {
            return edge.target();
        }
        return edge.target().equals(current) ? edge.source() : null;
    }

    private List<CodeEdge> rankEdges(String focusNodeId, List<CodeEdge> edges) {
        return edges.stream()
                .sorted(Comparator
                        .comparingInt((CodeEdge edge) -> edgeRank(focusNodeId, edge))
                        .thenComparing(CodeEdge::relation)
                        .thenComparing(CodeEdge::source)
                        .thenComparing(CodeEdge::target))
                .toList();
    }

    private int edgeRank(String focusNodeId, CodeEdge edge) {
        if ("calls".equals(edge.relation()) || "called-by".equals(edge.relation())) {
            return 0;
        }
        String otherId = edge.source().equals(focusNodeId) ? edge.target() : edge.source();
        CodeNode other = byId.get(otherId);
        if (other != null && isMethod(other)) {
            return 1;
        }
        if (other != null && isType(other)) {
            return isGeneric(other) ? 8 : 2;
        }
        return 10;
    }

    private int nodeRank(CodeNode node) {
        if (isMethod(node)) {
            return isGeneric(node) ? 5 : 0;
        }
        if (isType(node)) {
            return isGeneric(node) ? 6 : 1;
        }
        if ("file".equals(node.kind())) {
            return 7;
        }
        return 10;
    }

    private int pathWeight(String current, CodeEdge edge, String next) {
        CodeNode nextNode = byId.get(next);
        int weight = switch (edge.relation()) {
            case "calls", "called-by" -> 1;
            case "contains", "belongs-to-class" -> 2;
            case "extends", "implements" -> 3;
            case "references", "returns", "imports" -> 8;
            default -> 5;
        };
        if (nextNode != null && isGeneric(nextNode)) {
            weight += 25;
        }
        if (nextNode != null && "file".equals(nextNode.kind())) {
            weight += 25;
        }
        CodeNode currentNode = byId.get(current);
        if (currentNode != null && isMethod(currentNode) && nextNode != null && isMethod(nextNode)) {
            weight -= 1;
        }
        return Math.max(weight, 1);
    }

    private boolean isMethod(CodeNode node) {
        return node != null && ("method".equals(node.kind()) || "constructor".equals(node.kind()));
    }

    private boolean isType(CodeNode node) {
        return node != null && ("class".equals(node.kind())
                || "interface".equals(node.kind())
                || "record".equals(node.kind())
                || "enum".equals(node.kind())
                || "type".equals(node.kind()));
    }

    private boolean isGeneric(CodeNode node) {
        String id = node.id();
        return id.startsWith("java.")
                || id.startsWith("javax.")
                || id.startsWith("jakarta.")
                || id.startsWith("org.springframework.")
                || id.startsWith("org.slf4j.")
                || id.startsWith("com.fasterxml.")
                || "external_type".equals(node.kind())
                || "call_target".equals(node.kind());
    }

    private List<CodeNode> reconstruct(Map<String, String> previous, String end) {
        ArrayDeque<CodeNode> stack = new ArrayDeque<>();
        String current = end;
        while (current != null) {
            CodeNode node = byId.get(current);
            if (node != null) {
                stack.addFirst(node);
            }
            current = previous.get(current);
        }
        return new ArrayList<>(stack);
    }

    public record ExplainResult(CodeNode node, List<CodeEdge> edges) {
    }

    private record PathStep(String nodeId, int cost) {
    }
}
