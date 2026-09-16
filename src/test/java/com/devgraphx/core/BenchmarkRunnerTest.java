package com.devgraphx.core;

import com.devgraphx.core.benchmark.BenchmarkRunner;
import com.devgraphx.core.benchmark.BenchmarkRunner.BenchmarkResult;
import com.devgraphx.core.model.CodeGraph;
import com.devgraphx.core.scan.JavaSourceScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkRunnerTest {
    @TempDir
    Path tempDir;

    @Test
    void runsGenericBenchmarkQuestions() throws Exception {
        Path sourceRoot = tempDir.resolve("src/main/java");
        Path packageDir = sourceRoot.resolve("demo");
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve("Launcher.java"), """
                package demo;

                public class Launcher {
                    public static void main(String[] args) {
                        new Job(new Processor(new Sink())).run();
                    }
                }
                """);
        Files.writeString(packageDir.resolve("Job.java"), """
                package demo;

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
        Files.writeString(packageDir.resolve("Processor.java"), """
                package demo;

                public class Processor {
                    private final Sink sink;

                    public Processor(Sink sink) {
                        this.sink = sink;
                    }

                    public void process() {
                        sink.accept();
                    }
                }
                """);
        Files.writeString(packageDir.resolve("Sink.java"), """
                package demo;

                public class Sink {
                    public void accept() {
                    }
                }
                """);

        CodeGraph graph = new JavaSourceScanner(sourceRoot).scan();
        BenchmarkResult result = new BenchmarkRunner(graph, sourceRoot).run();

        assertTrue(result.corpusWords() > 0);
        assertTrue(result.corpusTokens() > result.averageQueryTokens());
        assertTrue(result.nodeCount() >= 4);
        assertTrue(result.edgeCount() > 0);
        assertEquals(4, result.corpusFiles().size());
        assertTrue(result.corpusFiles().stream().anyMatch(file -> file.path().equals("demo/Launcher.java")));

        List<String> questions = result.questions().stream()
                .map(BenchmarkRunner.QuestionResult::question)
                .toList();
        assertEquals(List.of(
                "what are the likely entry methods",
                "what are the highest-degree code nodes",
                "what method call chains are resolved",
                "how many method calls are unresolved"
        ), questions);
        result.questions().forEach(question -> assertTrue(question.tokens() > 0));

        String callAnswer = new BenchmarkRunner(graph, sourceRoot)
                .answerForQuestion("what method call chains are resolved");
        assertTrue(callAnswer.contains("demo.Job.run() -> demo.Processor.process()"));
        assertTrue(callAnswer.contains("demo.Processor.process() -> demo.Sink.accept()"));
    }
}
