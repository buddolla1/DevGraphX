package com.devgraphx.core.benchmark;

import com.devgraphx.core.model.CodeEdge;
import com.devgraphx.core.model.CodeGraph;
import com.devgraphx.core.model.CodeNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BenchmarkRunner {
    private static final List<String> QUESTIONS = List.of(
            "what are the likely entry methods",
            "what are the highest-degree code nodes",
            "what method call chains are resolved",
            "how many method calls are unresolved"
    );

    private final CodeGraph graph;
    private final Path repositoryRoot;
    private final Map<String, CodeNode> nodesById = new HashMap<>();
    private final Map<String, List<CodeEdge>> incoming = new HashMap<>();
    private final Map<String, List<CodeEdge>> outgoing = new HashMap<>();

    public BenchmarkRunner(CodeGraph graph, Path repositoryRoot) {
        this.graph = graph;
        this.repositoryRoot = repositoryRoot;
        for (CodeNode node : graph.getNodes()) {
            nodesById.put(node.id(), node);
        }
        for (CodeEdge edge : graph.getEdges()) {
            outgoing.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge);
            incoming.computeIfAbsent(edge.target(), ignored -> new ArrayList<>()).add(edge);
        }
    }

    public BenchmarkResult run() throws IOException {
        List<CorpusFile> corpusFiles = corpusFiles();
        int corpusWords = corpusFiles.stream().mapToInt(CorpusFile::words).sum();
        int corpusTokens = estimateTokensFromWords(corpusWords);
        List<QuestionResult> questions = QUESTIONS.stream()
                .map(question -> new QuestionResult(question, estimateTokens(answerForQuestion(question))))
                .toList();
        int averageQueryTokens = (int) Math.round(questions.stream()
                .mapToInt(QuestionResult::tokens)
                .average()
                .orElse(0));
        return new BenchmarkResult(corpusWords, corpusTokens, graph.getNodes().size(), graph.getEdges().size(),
                averageQueryTokens, corpusFiles, questions);
    }

    public String answerForQuestion(String question) {
        return switch (question) {
            case "what are the likely entry methods" -> likelyEntryMethodsAnswer();
            case "what are the highest-degree code nodes" -> highestDegreeAnswer();
            case "what method call chains are resolved" -> resolvedMethodCallsAnswer();
            case "how many method calls are unresolved" -> unresolvedCallsAnswer();
            default -> "";
        };
    }

    private String likelyEntryMethodsAnswer() {
        List<CodeNode> candidates = graph.getNodes().stream()
                .filter(this::isMethod)
                .filter(node -> node.methodName().equals("main") || incoming.getOrDefault(node.id(), List.of()).isEmpty())
                .sorted(Comparator.comparingInt(this::entryRank).thenComparing(CodeNode::id))
                .limit(10)
                .toList();
        return formatNodes("likely entry methods", candidates);
    }

    private String highestDegreeAnswer() {
        List<CodeNode> top = graph.getNodes().stream()
                .filter(node -> isCodeType(node) || isMethod(node))
                .sorted(Comparator.comparingInt(this::degree).reversed().thenComparing(CodeNode::id))
                .limit(12)
                .toList();
        return formatNodes("highest-degree code nodes", top);
    }

    private String resolvedMethodCallsAnswer() {
        List<String> calls = graph.getEdges().stream()
                .filter(edge -> "calls".equals(edge.relation()))
                .filter(edge -> isMethod(nodesById.get(edge.source())) && isMethod(nodesById.get(edge.target())))
                .sorted(Comparator.comparing(CodeEdge::source).thenComparing(CodeEdge::target))
                .limit(12)
                .map(edge -> nodesById.get(edge.source()).id() + " -> " + nodesById.get(edge.target()).id())
                .toList();
        return "resolved method calls\n" + String.join("\n", calls);
    }

    private String unresolvedCallsAnswer() {
        return "unresolved method calls\n- " + graph.getUnresolvedMethodCallCount();
    }

    private String formatNodes(String title, List<CodeNode> nodes) {
        StringBuilder builder = new StringBuilder(title).append('\n');
        for (CodeNode node : nodes) {
            builder.append("- ")
                    .append(node.id())
                    .append(" [").append(node.kind()).append("] ")
                    .append(node.location())
                    .append(" degree=").append(degree(node))
                    .append('\n');
        }
        return builder.toString();
    }

    private List<CorpusFile> corpusFiles() throws IOException {
        List<CorpusFile> files = new ArrayList<>();
        List<CodeNode> fileNodes = graph.getNodes().stream()
                .filter(node -> "file".equals(node.kind()))
                .sorted(Comparator.comparing(CodeNode::sourceFile))
                .toList();
        for (CodeNode node : fileNodes) {
            Path path = repositoryRoot.resolve(node.sourceFile()).normalize();
            if (Files.isRegularFile(path)) {
                files.add(new CorpusFile(node.sourceFile(), countWords(path)));
            }
        }
        return files;
    }

    private int countWords(Path path) {
        try {
            String content = Files.readString(path);
            String trimmed = content.trim();
            if (trimmed.isEmpty()) {
                return 0;
            }
            return trimmed.split("\\s+").length;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }

    private int estimateTokens(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        return estimateTokensFromWords(trimmed.split("\\s+").length);
    }

    private int estimateTokensFromWords(int words) {
        return (int) Math.ceil(words * 4.0 / 3.0);
    }

    private int degree(CodeNode node) {
        return incoming.getOrDefault(node.id(), List.of()).size()
                + outgoing.getOrDefault(node.id(), List.of()).size();
    }

    private int entryRank(CodeNode node) {
        if (node.methodName().equals("main")) {
            return 0;
        }
        if ("public".equals(node.visibility())) {
            return 1;
        }
        return 2;
    }

    private boolean isMethod(CodeNode node) {
        return node != null && ("method".equals(node.kind()) || "constructor".equals(node.kind()));
    }

    private boolean isCodeType(CodeNode node) {
        return node != null && ("class".equals(node.kind())
                || "interface".equals(node.kind())
                || "record".equals(node.kind())
                || "enum".equals(node.kind()));
    }

    public record QuestionResult(String question, int tokens) {
        public double reduction(int corpusTokens) {
            if (tokens == 0) {
                return 0;
            }
            return corpusTokens / (double) tokens;
        }
    }

    public record CorpusFile(String path, int words) {
    }

    public record BenchmarkResult(
            int corpusWords,
            int corpusTokens,
            int nodeCount,
            int edgeCount,
            int averageQueryTokens,
            List<CorpusFile> corpusFiles,
            List<QuestionResult> questions
    ) {
        public double averageReduction() {
            if (averageQueryTokens == 0) {
                return 0;
            }
            return corpusTokens / (double) averageQueryTokens;
        }
    }
}
