# DevGraphX

DevGraphX is a Java 21 code graph tool that scans Java repositories with JavaParser and writes a portable graph for graph-first targeted retrieval. It can be used as a standalone CLI or as a Gradle plugin.

## Project Structure

- `devgraphx-core`: generic scanner, graph model, JSON IO, query, and benchmark logic
- `devgraphx-cli`: standalone runnable CLI JAR
- `devgraphx-gradle-plugin`: Gradle plugin that reuses `devgraphx-core`

Build once, then use the same runnable JAR against any Java repository:

```sh
./gradlew clean build
java -jar devgraphx-cli/build/libs/devgraphx.jar scan /path/to/project --output /path/to/project/devgraphx.json
```

## Commands

Scan a Java repository:

```sh
java -jar devgraphx-cli/build/libs/devgraphx.jar scan /path/to/project
```

Write the graph to a specific location:

```sh
java -jar devgraphx-cli/build/libs/devgraphx.jar scan /path/to/project --output /path/to/project/devgraphx.json
```

Limit or exclude paths:

```sh
java -jar devgraphx-cli/build/libs/devgraphx.jar scan /path/to/project --include module-a/src/main/java --exclude module-a/build
```

Exclude test sources:

```sh
java -jar devgraphx-cli/build/libs/devgraphx.jar scan /path/to/project --include-tests false
```

Explain a node:

```sh
java -jar devgraphx-cli/build/libs/devgraphx.jar explain SomeClass /path/to/project/devgraphx.json
```

Find affected callers and references:

```sh
java -jar devgraphx-cli/build/libs/devgraphx.jar affected SomeClass /path/to/project/devgraphx.json
java -jar devgraphx-cli/build/libs/devgraphx.jar affected SomeClass.someMethod /path/to/project/devgraphx.json
```

Find a path between nodes:

```sh
java -jar devgraphx-cli/build/libs/devgraphx.jar path ClassA ClassB /path/to/project/devgraphx.json
```

Run the token-reduction benchmark:

```sh
java -jar devgraphx-cli/build/libs/devgraphx.jar benchmark /path/to/project /path/to/project/devgraphx.json
```

## Gradle Plugin

Publish the plugin and core dependency to Maven Local:

```sh
./gradlew publishToMavenLocal
```

In a separate Java Gradle project, add Maven Local to plugin resolution in `settings.gradle`:

```groovy
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
}
```

Apply the plugin in `build.gradle`:

```groovy
plugins {
    id 'java'
    id 'com.devgraphx' version '1.0.0'
}

devGraphX {
    outputFile = file("$rootDir/devgraphx.json")
    includeTests = false
}
```

Run:

```sh
./gradlew devGraphX
```

By default the task scans the consuming project root and writes `<project-root>/devgraphx.json`.

## Graph

The default output file is `devgraphx.json` in the analyzed repository root. Source file paths stored in the graph are relative to the analyzed repository.

DevGraphX records class-level relationships and method-level relationships where they can be statically resolved. Unresolved method calls are counted instead of being emitted as invented method edges.
