package com.devgraphx.core;

import com.devgraphx.core.io.GraphJson;
import com.devgraphx.core.model.CodeGraph;
import com.devgraphx.core.model.CodeNode;
import com.devgraphx.core.query.GraphIndex;
import com.devgraphx.core.scan.JavaSourceScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaSourceScannerTest {
    @TempDir
    Path tempDir;

    @Test
    void scansClassesMethodsCallsAndReferences() throws Exception {
        Path sourceRoot = tempDir.resolve("src/main/java");
        Path packageDir = sourceRoot.resolve("demo");
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve("ApiClient.java"), """
                package demo;

                import java.net.http.HttpClient;

                public class ApiClient {
                    private final HttpClient httpClient;

                    public ApiClient(HttpClient httpClient) {
                        this.httpClient = httpClient;
                    }

                    public Response fetch(Request request) {
                        return parse(request);
                    }

                    private Response parse(Request request) {
                        return new Response();
                    }
                }
                """);
        Files.writeString(packageDir.resolve("Coordinator.java"), """
                package demo;

                public class Coordinator {
                    private final ApiClient apiClient;

                    public Coordinator(ApiClient apiClient) {
                        this.apiClient = apiClient;
                    }

                    public void sync(Request request) {
                        apiClient.fetch(request);
                    }
                }
                """);
        Files.writeString(packageDir.resolve("Request.java"), "package demo; public class Request {}\n");
        Files.writeString(packageDir.resolve("Response.java"), "package demo; public class Response {}\n");

        CodeGraph graph = new JavaSourceScanner(sourceRoot).scan();
        GraphIndex index = new GraphIndex(graph);

        assertTrue(graph.getNodes().stream().anyMatch(node -> node.id().equals("demo.ApiClient")));
        assertTrue(graph.getNodes().stream().anyMatch(node -> node.id().equals("demo.ApiClient.fetch(demo.Request)")));
        CodeNode apiClient = index.explain("ApiClient").node();
        assertEquals("demo/ApiClient.java", apiClient.sourceFile());
        assertEquals(5, apiClient.startLine());
        assertEquals(19, apiClient.endLine());
        assertEquals("demo/ApiClient.java:5-19", apiClient.location());

        CodeNode fetch = index.explain("demo.ApiClient#fetch").node();
        assertEquals("demo/ApiClient.java:12-14", fetch.location());
        assertEquals("fetch", fetch.methodName());
        assertEquals("demo.ApiClient", fetch.fullyQualifiedClassName());
        assertEquals("public", fetch.visibility());
        assertEquals("demo.Response", fetch.returnType());
        assertEquals(List.of("demo.Request"), fetch.parameterTypes());

        CodeNode fileNode = index.explain("file:demo/ApiClient.java").node();
        assertEquals("demo/ApiClient.java:1-19", fileNode.location());

        assertTrue(graph.getEdges().stream().anyMatch(edge ->
                edge.source().equals("demo.ApiClient.fetch(demo.Request)")
                        && edge.target().equals("demo.ApiClient.parse(demo.Request)")
                        && edge.relation().equals("calls")));
        assertTrue(graph.getEdges().stream().anyMatch(edge ->
                edge.source().equals("demo.Coordinator.sync(demo.Request)")
                        && edge.target().equals("demo.ApiClient.fetch(demo.Request)")
                        && edge.relation().equals("calls")));
        assertTrue(graph.getEdges().stream().anyMatch(edge ->
                edge.source().equals("demo.ApiClient.fetch(demo.Request)")
                        && edge.target().equals("demo.Coordinator.sync(demo.Request)")
                        && edge.relation().equals("called-by")));
        assertTrue(graph.getEdges().stream().anyMatch(edge ->
                edge.source().equals("demo.Coordinator")
                        && edge.target().equals("demo.ApiClient")
                        && edge.relation().equals("references")));

        GraphIndex.ExplainResult explain = index.explain("ApiClient");
        assertEquals("demo.ApiClient", explain.node().id());
        assertFalse(explain.edges().isEmpty());

        assertTrue(index.affected("ApiClient").stream().anyMatch(node -> node.id().equals("demo.Coordinator")));
        assertFalse(index.path("Coordinator", "Response").isEmpty());

        Path graphFile = tempDir.resolve("devgraphx.json");
        GraphJson.write(graph, graphFile);

    }

    @Test
    void scansRepositoryRootAndDiscoversModules() throws Exception {
        Path repo = tempDir.resolve("repo");
        Path moduleA = repo.resolve("module-a/src/main/java/demo/a");
        Path moduleB = repo.resolve("module-b/src/main/java/demo/b");
        Files.createDirectories(moduleA);
        Files.createDirectories(moduleB);
        Files.writeString(moduleA.resolve("Job.java"), """
                package demo.a;

                import demo.b.Processor;

                public class Job {
                    private final Processor processor;

                    public Job(Processor processor) {
                        this.processor = processor;
                    }

                    public void run() {
                        processor.process();
                    }
                }
                """);
        Files.writeString(moduleB.resolve("Processor.java"), """
                package demo.b;

                public class Processor {
                    public void process() {
                    }
                }
                """);
        Files.createDirectories(repo.resolve("module-a/build/generated"));
        Files.writeString(repo.resolve("module-a/build/generated/Ignored.java"), "class Ignored {}\n");

        Path graphFile = repo.resolve("custom-devgraphx.json");
        GraphJson.write(new JavaSourceScanner(repo).scan(), graphFile);

        CodeGraph graph = GraphJson.read(graphFile);
        assertTrue(graph.getNodes().stream().anyMatch(node -> node.id().equals("demo.a.Job.run()")
                && node.sourceFile().equals("module-a/src/main/java/demo/a/Job.java")));
        assertTrue(graph.getEdges().stream().anyMatch(edge ->
                edge.source().equals("demo.a.Job.run()")
                        && edge.target().equals("demo.b.Processor.process()")
                        && edge.relation().equals("calls")));
        assertFalse(graph.getNodes().stream().anyMatch(node -> node.id().equals("Ignored")));
    }

    @Test
    void distinguishesOverloadsAndReportsCallers() throws Exception {
        Path sourceRoot = tempDir.resolve("overloads/src/main/java");
        Path packageDir = sourceRoot.resolve("demo");
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve("Worker.java"), """
                package demo;

                public class Worker {
                    public String convert(String value) {
                        return value;
                    }

                    public String convert(Integer value) {
                        return value.toString();
                    }
                }
                """);
        Files.writeString(packageDir.resolve("Caller.java"), """
                package demo;

                public class Caller {
                    private final Worker worker;

                    public Caller(Worker worker) {
                        this.worker = worker;
                    }

                    public String run(String value) {
                        return worker.convert(value);
                    }
                }
                """);

        CodeGraph graph = new JavaSourceScanner(sourceRoot).scan();
        GraphIndex index = new GraphIndex(graph);

        assertTrue(graph.getNodes().stream().anyMatch(node -> node.id().equals("demo.Worker.convert(java.lang.String)")));
        assertTrue(graph.getNodes().stream().anyMatch(node -> node.id().equals("demo.Worker.convert(java.lang.Integer)")));
        assertTrue(graph.getEdges().stream().anyMatch(edge ->
                edge.source().equals("demo.Caller.run(java.lang.String)")
                        && edge.target().equals("demo.Worker.convert(java.lang.String)")
                        && edge.relation().equals("calls")));

        GraphIndex.ExplainResult explain = index.explain("Worker.convert(java.lang.String)");
        assertEquals("demo.Worker.convert(java.lang.String)", explain.node().id());
        assertTrue(explain.edges().stream().anyMatch(edge ->
                edge.source().equals("demo.Worker.convert(java.lang.String)")
                        && edge.target().equals("demo.Caller.run(java.lang.String)")
                        && edge.relation().equals("called-by")));
    }

}
