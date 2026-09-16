package com.devgraphx.gradle;

import com.devgraphx.core.io.GraphJson;
import com.devgraphx.core.model.CodeGraph;
import com.devgraphx.core.scan.JavaSourceScanner;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.nio.file.Path;
import java.util.List;

public abstract class DevGraphXTask extends DefaultTask {
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getScanRoot();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @Input
    public abstract Property<Boolean> getIncludeTests();

    @TaskAction
    public void generateGraph() throws Exception {
        Path root = getScanRoot().get().getAsFile().toPath();
        Path output = getOutputFile().get().getAsFile().toPath();
        CodeGraph graph = new JavaSourceScanner(root, List.of(), List.of(), getIncludeTests().get()).scan();
        GraphJson.write(graph, output);

        long filesScanned = graph.getNodes().stream()
                .filter(node -> "file".equals(node.kind()))
                .count();

        getLogger().lifecycle("DevGraphX scan complete");
        getLogger().lifecycle("Files scanned: {}", filesScanned);
        getLogger().lifecycle("Nodes: {}", graph.getNodes().size());
        getLogger().lifecycle("Edges: {}", graph.getEdges().size());
        getLogger().lifecycle("Output: {}", output.toAbsolutePath().normalize());
    }
}
