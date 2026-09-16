Add Gradle plugin support to the existing DevGraphX project.

Goal:

I want DevGraphX to be reusable across multiple Gradle Java projects.

A consuming project should eventually be able to use:

plugins {
id 'com.devgraphx' version '1.0.0'
}

and run:

./gradlew devGraphX

which analyzes the consuming project and generates:

devgraphx.json


## Architecture

Keep the DevGraphX graph engine independent from Gradle.

Prefer a structure such as:

DevGraphX/
devgraphx-core/
devgraphx-cli/
devgraphx-gradle-plugin/

If restructuring the current project into multiple modules would create
unnecessary risk, first explain the minimum safe restructuring required
and then implement it.

The important requirement is that the Gradle plugin reuses the existing
DevGraphX scanning and graph logic.

Do NOT duplicate graph-building logic inside the Gradle plugin.


## Gradle Plugin

Create a Gradle plugin with plugin id:

    com.devgraphx

The plugin should register a task:

    devGraphX

A consuming project should be able to run:

    ./gradlew devGraphX


## devGraphX Task

By default the task should:

1. Use the consuming project's root directory as the scan root.

2. Discover Java source using the existing generic DevGraphX scanner.

3. Generate:

       <project-root>/devgraphx.json

4. Print a concise summary such as:

       DevGraphX scan complete
       Files scanned: ...
       Nodes: ...
       Edges: ...
       Output: .../devgraphx.json


## Configuration

Provide a Gradle extension so projects can optionally configure DevGraphX.

Desired example:

devGraphX {
outputFile = file("$rootDir/devgraphx.json")
includeTests = false
}

Use sensible defaults so configuration is NOT required.

Do not introduce application-specific configuration.


## Generic Requirements

The plugin must work with different Java Gradle projects.

Do NOT hardcode:

- project names
- package names
- source directories belonging to the current application
- class names
- method names
- Controller/Service/Repository assumptions
- business terminology
- absolute local paths

Use the consuming Gradle project's information and DevGraphX's generic
source discovery.


## Multi-Module Projects

Support Gradle multi-module projects where reasonably possible.

Running:

    ./gradlew devGraphX

from the root project should be able to analyze Java source in relevant
subprojects/modules.

Do not hardcode module names.


## Keep CLI Support

Do not remove standalone CLI support.

Both of these should remain possible:

    java -jar devgraphx.jar scan /path/to/project

and:

    ./gradlew devGraphX


## Local Plugin Testing

For now, I do NOT want to publish DevGraphX publicly.

Set it up so I can test/install the plugin locally first.

Use Maven Local or Gradle's recommended local plugin development approach.

Show me exactly how to:

1. Build DevGraphX.
2. Publish/install the plugin locally.
3. Add the plugin to a separate test Java project.
4. Run:

       ./gradlew devGraphX

5. Verify devgraphx.json was generated.


## Automated Tests

Add tests for the Gradle plugin using Gradle TestKit.

At minimum test:

- plugin can be applied
- devGraphX task exists
- task runs against a small Java project
- devgraphx.json is generated
- source files are discovered
- configuration overrides work

Do not depend only on the current application for testing.


## Existing Functionality

Preserve existing DevGraphX functionality:

- scan
- explain
- affected
- path
- benchmark
- method-level relationships
- source line ranges
- graph ranking/deprioritization

Do not modify application business logic.


## Final Report

When finished show:

- final project/module structure
- files created
- files modified
- Gradle plugin id
- Gradle task name
- plugin version
- local plugin artifact location
- exact command to publish/install locally
- exact consuming-project configuration
- exact command to run DevGraphX
- generated graph location
- TestKit test results
- any limitations for multi-module projects