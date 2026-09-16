package com.devgraphx.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public final class DevGraphXPlugin implements Plugin<Project> {
    public static final String EXTENSION_NAME = "devGraphX";
    public static final String TASK_NAME = "devGraphX";

    @Override
    public void apply(Project project) {
        DevGraphXExtension extension = project.getExtensions().create(EXTENSION_NAME, DevGraphXExtension.class);
        extension.getOutputFile().convention(project.getLayout().getProjectDirectory().file("devgraphx.json"));
        extension.getIncludeTests().convention(true);

        project.getTasks().register(TASK_NAME, DevGraphXTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Analyzes Java source and writes a DevGraphX code graph.");
            task.getScanRoot().convention(project.getLayout().getProjectDirectory());
            task.getOutputFile().convention(extension.getOutputFile());
            task.getIncludeTests().convention(extension.getIncludeTests());
        });
    }
}
