# How to Use DevGraphX

DevGraphX is a standalone Java code-graph CLI. Build it once, then use the same JAR to scan any Java repository.

## 1. Build DevGraphX

From the workspace root:

```sh
./gradlew clean build
```

This creates:

```sh
devgraphx-cli/build/libs/devgraphx.jar
```

## 2. Scan a Java Project

Scan a repository and write the graph to the default location:

```sh
java -jar devgraphx-cli/build/libs/devgraphx.jar scan /path/to/java-project
```

By default, DevGraphX writes:

```sh
/path/to/java-project/devgraphx.json
```

Specify an output file:

```sh
java -jar devgraphx-cli/build/libs/devgraphx.jar scan /path/to/java-project \
  --output /path/to/java-project/devgraphx.json
```

## 3. Query the Graph

Explain a class or method:

```sh
java -jar devgraphx-cli/build/libs/devgraphx.jar explain SomeClass /path/to/java-project/devgraphx.json
java -jar devgraphx-cli/build/libs/devgraphx.jar explain SomeClass.someMethod /path/to/java-project/devgraphx.json
```

Find affected callers and references:

```sh
java -jar devgraphx-cli/build/libs/devgraphx.jar affected SomeClass /path/to/java-project/devgraphx.json
java -jar devgraphx-cli/build/libs/devgraphx.jar affected SomeClass.someMethod /path/to/java-project/devgraphx.json
```

Find a path between two nodes:

```sh
java -jar devgraphx-cli/build/libs/devgraphx.jar path ClassA ClassB /path/to/java-project/devgraphx.json
```

Run the benchmark:

```sh
java -jar devgraphx-cli/build/libs/devgraphx.jar benchmark /path/to/java-project /path/to/java-project/devgraphx.json
```

## 4. Scan Multiple Projects

Use the same JAR against different repositories:

```sh
java -jar devgraphx-cli/build/libs/devgraphx.jar scan ~/projects/project-a --output ~/projects/project-a/devgraphx.json
java -jar devgraphx-cli/build/libs/devgraphx.jar scan ~/projects/project-b --output ~/projects/project-b/devgraphx.json
```

No DevGraphX source changes or rebuilds are needed when switching target projects.

## 5. Optional Scan Controls

Include only a specific path:

```sh
java -jar devgraphx-cli/build/libs/devgraphx.jar scan /path/to/java-project \
  --include module-a/src/main/java
```

Exclude a path:

```sh
java -jar devgraphx-cli/build/libs/devgraphx.jar scan /path/to/java-project \
  --exclude module-a/build
```

Exclude test sources:

```sh
java -jar devgraphx-cli/build/libs/devgraphx.jar scan /path/to/java-project \
  --include-tests false
```

## 7. Use the Gradle Plugin Locally

Publish DevGraphX to Maven Local:

```sh
./gradlew publishToMavenLocal
```

In a separate Java Gradle project, add Maven Local to `settings.gradle`:

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

Verify:

```sh
test -f devgraphx.json
```

## 6. What the Graph Contains

DevGraphX stores:

- class nodes with relative source paths and line ranges
- method and constructor nodes with stable signatures
- class relationships such as references, imports, inheritance, and containment
- method relationships such as calls and called-by
- unresolved method-call count when calls cannot be resolved safely

Generated graph files belong to the analyzed project, not to the DevGraphX installation.
