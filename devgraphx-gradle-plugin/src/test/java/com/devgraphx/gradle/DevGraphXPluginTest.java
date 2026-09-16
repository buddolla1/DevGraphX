package com.devgraphx.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevGraphXPluginTest {
    @TempDir
    Path projectDir;

    @Test
    void pluginCanBeAppliedAndRegistersTask() throws Exception {
        writeProject("""
                plugins {
                    id 'java'
                    id 'com.devgraphx'
                }
                """);

        BuildResult result = gradle("tasks", "--all");

        assertTrue(result.getOutput().contains("devGraphX"));
    }

    @Test
    void taskScansSmallJavaProjectAndWritesDefaultGraph() throws Exception {
        writeProject("""
                plugins {
                    id 'java'
                    id 'com.devgraphx'
                }
                """);
        writeJava("src/main/java/demo/App.java", """
                package demo;

                public class App {
                    public void run() {
                        helper();
                    }

                    private void helper() {
                    }
                }
                """);

        BuildResult result = gradle("devGraphX");

        assertEquals(SUCCESS, result.task(":devGraphX").getOutcome());
        assertTrue(result.getOutput().contains("DevGraphX scan complete"));
        assertTrue(result.getOutput().contains("Files scanned: 1"));
        Path graph = projectDir.resolve("devgraphx.json");
        assertTrue(Files.exists(graph));
        String json = Files.readString(graph);
        assertTrue(json.contains("demo.App"));
        assertTrue(json.contains("demo/App.java"));
    }

    @Test
    void taskDiscoversJavaSourcesInSubprojects() throws Exception {
        Files.writeString(projectDir.resolve("settings.gradle"), "include 'api', 'impl'\n");
        writeProject("""
                plugins {
                    id 'com.devgraphx'
                }
                """);
        writeJava("api/src/main/java/demo/api/Api.java", "package demo.api; public interface Api { void call(); }\n");
        writeJava("impl/src/main/java/demo/impl/Impl.java", """
                package demo.impl;
                import demo.api.Api;
                public class Impl implements Api {
                    public void call() {
                    }
                }
                """);

        gradle("devGraphX");

        String json = Files.readString(projectDir.resolve("devgraphx.json"));
        assertTrue(json.contains("api/src/main/java/demo/api/Api.java"));
        assertTrue(json.contains("impl/src/main/java/demo/impl/Impl.java"));
    }

    @Test
    void configurationOverridesOutputFileAndExcludesTests() throws Exception {
        writeProject("""
                plugins {
                    id 'java'
                    id 'com.devgraphx'
                }

                devGraphX {
                    outputFile = file("$buildDir/reports/custom-devgraphx.json")
                    includeTests = false
                }
                """);
        writeJava("src/main/java/demo/App.java", "package demo; public class App {}\n");
        writeJava("src/test/java/demo/AppTest.java", "package demo; public class AppTest {}\n");

        gradle("devGraphX");

        Path customGraph = projectDir.resolve("build/reports/custom-devgraphx.json");
        assertTrue(Files.exists(customGraph));
        assertFalse(Files.exists(projectDir.resolve("devgraphx.json")));
        String json = Files.readString(customGraph);
        assertTrue(json.contains("demo.App"));
        assertFalse(json.contains("demo.AppTest"));
        assertFalse(json.contains("src/test/java/demo/AppTest.java"));
    }

    private BuildResult gradle(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(arguments)
                .withPluginClasspath()
                .build();
    }

    private void writeProject(String buildFile) throws Exception {
        if (!Files.exists(projectDir.resolve("settings.gradle"))) {
            Files.writeString(projectDir.resolve("settings.gradle"), "");
        }
        Files.writeString(projectDir.resolve("build.gradle"), buildFile);
    }

    private void writeJava(String relativePath, String source) throws Exception {
        Path file = projectDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }
}
