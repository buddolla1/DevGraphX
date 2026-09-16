package com.devgraphx.gradle;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

public abstract class DevGraphXExtension {
    public abstract RegularFileProperty getOutputFile();

    public abstract Property<Boolean> getIncludeTests();
}
