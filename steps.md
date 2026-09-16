# Adding a New Project to DevGraphX

Use these steps when you want DevGraphX to analyze another Java project.

## Option 1: Scan a Project with the DevGraphX CLI

1. Go to the DevGraphX repository:

   ```sh
   cd /Users/maheswarbuddolla/softwares/DevGraphX
   ```

2. Build DevGraphX:

   ```sh
   ./gradlew clean build
   ```

3. Run the scanner against the new Java project:

   ```sh
   java -jar devgraphx-cli/build/libs/devgraphx.jar scan /path/to/new-project
   ```

4. Check the generated graph file:

   ```text
   /path/to/new-project/devgraphx.json
   ```

5. To choose the output file manually:

   ```sh
   java -jar devgraphx-cli/build/libs/devgraphx.jar scan /path/to/new-project --output /path/to/new-project/devgraphx.json
   ```

6. To exclude test sources:

   ```sh
   java -jar devgraphx-cli/build/libs/devgraphx.jar scan /path/to/new-project --include-tests false
   ```

7. Use the generated graph:

   ```sh
   java -jar devgraphx-cli/build/libs/devgraphx.jar explain SomeClass /path/to/new-project/devgraphx.json
   java -jar devgraphx-cli/build/libs/devgraphx.jar affected SomeClass /path/to/new-project/devgraphx.json
   ```

## Option 2: Add DevGraphX as a Gradle Plugin

1. Publish DevGraphX to Maven Local:

   ```sh
   cd /Users/maheswarbuddolla/softwares/DevGraphX
   ./gradlew publishToMavenLocal
   ```

2. In the new Java Gradle project's `settings.gradle`, add `mavenLocal()` to plugin resolution:

   ```groovy
   pluginManagement {
       repositories {
           mavenLocal()
           gradlePluginPortal()
           mavenCentral()
       }
   }
   ```

3. In the new project's `build.gradle`, apply the plugin:

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

4. Run DevGraphX from the new project:

   ```sh
   ./gradlew devGraphX
   ```

5. Check the output:

   ```text
   devgraphx.json
   ```

## Option 3: Add a New Module Inside DevGraphX

Use these steps only when you are adding new source code to the DevGraphX repository itself.

1. Create a new module directory:

   ```text
   devgraphx-new-module/
   ```

2. Add standard Java source directories:

   ```text
   devgraphx-new-module/src/main/java
   devgraphx-new-module/src/test/java
   ```

3. Add `devgraphx-new-module/build.gradle`.

4. Register the module in `settings.gradle`:

   ```groovy
   include 'devgraphx-new-module'
   ```

5. Add a basic module build file:

   ```groovy
   plugins {
       id 'java-library'
   }

   dependencies {
       implementation project(':devgraphx-core')

       testImplementation 'org.junit.jupiter:junit-jupiter:5.10.3'
       testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
   }
   ```

6. Build everything:

   ```sh
   ./gradlew clean build
   ```
