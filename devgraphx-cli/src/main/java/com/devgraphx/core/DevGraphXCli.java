package com.devgraphx.core;

import com.devgraphx.core.benchmark.BenchmarkRunner;
import com.devgraphx.core.benchmark.BenchmarkRunner.BenchmarkResult;
import com.devgraphx.core.benchmark.BenchmarkRunner.QuestionResult;
import com.devgraphx.core.io.GraphJson;
import com.devgraphx.core.model.CodeEdge;
import com.devgraphx.core.model.CodeGraph;
import com.devgraphx.core.model.CodeNode;
import com.devgraphx.core.query.GraphIndex;
import com.devgraphx.core.scan.JavaSourceScanner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class DevGraphXCli {
    private static final Path DEFAULT_GRAPH = Path.of("devgraphx.json");

    private DevGraphXCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }

        switch (args[0]) {
            case "scan", "build" -> scan(args);
            case "explain" -> explain(args);
            case "affected" -> affected(args);
            case "path" -> path(args);
            case "benchmark" -> benchmark(args);
            default -> {
                usage();
                System.exit(2);
            }
        }
    }

    private static void scan(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: scan <repoRoot> [--output <graphFile>] [--include <path>] [--exclude <path>] [--include-tests true|false]");
        }
        ScanOptions options = parseScanOptions(args);
        CodeGraph graph = new JavaSourceScanner(
                options.repoRoot(),
                options.includes(),
                options.excludes(),
                options.includeTests()
        ).scan();
        Path output = options.output();
        GraphJson.write(graph, output);
        long methodNodes = graph.getNodes().stream()
                .filter(node -> "method".equals(node.kind()) || "constructor".equals(node.kind()))
                .count();
        long methodEdges = graph.getEdges().stream()
                .filter(edge -> "calls".equals(edge.relation()) || "called-by".equals(edge.relation()))
                .count();
        System.out.printf("Wrote %s (%d nodes, %d edges, %d method nodes, %d method edges, %d unresolved method calls)%n",
                output,
                graph.getNodes().size(),
                graph.getEdges().size(),
                methodNodes,
                methodEdges,
                graph.getUnresolvedMethodCallCount());
    }

    private static void explain(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException("Usage: explain <node> [graphFile]");
        }
        GraphIndex index = load(args);
        GraphIndex.ExplainResult result = index.explain(args[1]);
        CodeNode node = result.node();
        System.out.printf("Node: %s%n", node.label());
        System.out.printf("  ID:     %s%n", node.id());
        System.out.printf("  Kind:   %s%n", node.kind());
        System.out.printf("  Source: %s%n", node.location());
        if (isMethod(node)) {
            printMethodDetails(node, "  ");
        }
        System.out.printf("Connections (%d):%n", result.edges().size());
        for (CodeEdge edge : result.edges()) {
            String direction = edge.target().equals(node.id()) ? "<--" : "-->";
            String other = edge.target().equals(node.id()) ? edge.source() : edge.target();
            CodeNode otherNode = index.node(other).orElse(null);
            System.out.printf("  %s %s [%s] %s%s%n",
                    direction,
                    otherNode == null ? other : otherNode.id(),
                    edge.relation(),
                    edge.sourceFile(),
                    edge.line() > 0 ? ":" + edge.line() : "");
            if (otherNode != null && isMethod(otherNode)) {
                printMethodDetails(otherNode, "      ");
            }
        }
    }

    private static void affected(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException("Usage: affected <node> [graphFile]");
        }
        GraphIndex index = load(args);
        List<CodeNode> affected = index.affected(args[1]);
        System.out.printf("Affected nodes for %s:%n", args[1]);
        affected.forEach(node -> {
            if (isMethod(node)) {
                System.out.printf("- %s [%s]%n", node.id(), node.kind());
                printMethodDetails(node, "  ");
            } else {
                System.out.printf("- %s [%s] %s%n", node.label(), node.kind(), node.location());
            }
        });
    }

    private static void path(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            throw new IllegalArgumentException("Usage: path <from> <to> [graphFile]");
        }
        Path graphFile = args.length == 4 ? Path.of(args[3]) : DEFAULT_GRAPH;
        GraphIndex index = new GraphIndex(GraphJson.read(graphFile));
        List<CodeNode> path = index.path(args[1], args[2]);
        if (path.isEmpty()) {
            System.out.println("No path found.");
            return;
        }
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) {
                System.out.print(" -> ");
            }
            CodeNode node = path.get(i);
            System.out.print(node.label());
            if (!node.location().isBlank()) {
                System.out.print(" [" + node.location() + "]");
            }
        }
        System.out.println();
    }

    private static void benchmark(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException("Usage: benchmark <sourceRoot> [graphFile]");
        }
        Path sourceRoot = Path.of(args[1]);
        Path graphFile = args.length == 3 ? Path.of(args[2]) : DEFAULT_GRAPH;
        BenchmarkResult result = new BenchmarkRunner(GraphJson.read(graphFile), sourceRoot).run();

        System.out.println("DevGraphX token reduction benchmark");
        System.out.println("--------------------------------------------------");
        System.out.printf("  Corpus:          %,d words -> ~%,d tokens (naive)%n",
                result.corpusWords(), result.corpusTokens());
        System.out.printf("  Graph:           %,d nodes, %,d edges%n", result.nodeCount(), result.edgeCount());
        System.out.printf("  Avg query cost:  ~%,d tokens%n", result.averageQueryTokens());
        System.out.printf("  Reduction:       %.1fx fewer tokens per query%n", result.averageReduction());
        System.out.printf("  Corpus files:    %,d files from graph file nodes%n", result.corpusFiles().size());
        System.out.println();
        System.out.println("  Files counted:");
        result.corpusFiles().forEach(file -> System.out.printf("    %,5d  %s%n", file.words(), file.path()));
        System.out.println();
        System.out.println("  Per question:");
        for (QuestionResult question : result.questions()) {
            System.out.printf("    [%.1fx] %s (~%,d tokens)%n",
                    question.reduction(result.corpusTokens()), question.question(), question.tokens());
        }
    }

    private static GraphIndex load(String[] args) throws Exception {
        Path graphFile = args.length == 3 ? Path.of(args[2]) : DEFAULT_GRAPH;
        return new GraphIndex(GraphJson.read(graphFile));
    }

    private static ScanOptions parseScanOptions(String[] args) {
        Path repoRoot = Path.of(args[1]).toAbsolutePath().normalize();
        Path output = repoRoot.resolve(DEFAULT_GRAPH).normalize();
        List<Path> includes = new ArrayList<>();
        List<Path> excludes = new ArrayList<>();
        boolean includeTests = true;

        for (int index = 2; index < args.length; index++) {
            String arg = args[index];
            switch (arg) {
                case "--output", "-o" -> {
                    index = requireValue(args, index, arg);
                    output = Path.of(args[index]).toAbsolutePath().normalize();
                }
                case "--include" -> {
                    index = requireValue(args, index, arg);
                    includes.add(repoRoot.resolve(args[index]).normalize());
                }
                case "--exclude" -> {
                    index = requireValue(args, index, arg);
                    excludes.add(repoRoot.resolve(args[index]).normalize());
                }
                case "--include-tests" -> {
                    index = requireValue(args, index, arg);
                    includeTests = Boolean.parseBoolean(args[index]);
                }
                default -> {
                    if (!arg.startsWith("-") && args.length == 3) {
                        output = Path.of(arg).toAbsolutePath().normalize();
                    } else {
                        throw new IllegalArgumentException("Unknown scan option: " + arg);
                    }
                }
            }
        }
        if (!Files.isDirectory(repoRoot)) {
            throw new IllegalArgumentException("Repository root does not exist or is not a directory: " + repoRoot);
        }
        return new ScanOptions(repoRoot, output, includes, excludes, includeTests);
    }

    private static int requireValue(String[] args, int index, String option) {
        if (index + 1 >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return index + 1;
    }

    private static boolean isMethod(CodeNode node) {
        return "method".equals(node.kind()) || "constructor".equals(node.kind());
    }

    private static void printMethodDetails(CodeNode node, String indent) {
        System.out.printf("%sfile: %s%n", indent, node.sourceFile());
        System.out.printf("%slines: %d-%d%n", indent, node.startLine(), node.endLine());
        if (!node.signature().isBlank()) {
            System.out.printf("%ssignature: %s%n", indent, node.signature());
        }
        if (!node.visibility().isBlank()) {
            System.out.printf("%svisibility: %s%n", indent, node.visibility());
        }
        if (!node.returnType().isBlank()) {
            System.out.printf("%sreturn: %s%n", indent, node.returnType());
        }
    }

    private static void usage() {
        System.out.println("""
                Usage:
                  devgraphx scan <repoRoot> [--output <graphFile>] [--include <path>] [--exclude <path>] [--include-tests true|false]
                  devgraphx explain <node> [graphFile]
                  devgraphx affected <node> [graphFile]
                  devgraphx path <from> <to> [graphFile]
                  devgraphx benchmark <repoRoot> [graphFile]
                """);
    }

    private record ScanOptions(
            Path repoRoot,
            Path output,
            List<Path> includes,
            List<Path> excludes,
            boolean includeTests
    ) {
    }
}
