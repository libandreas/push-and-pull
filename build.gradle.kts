buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.commonmark:commonmark:0.22.0")
    }
}

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "com.deploymenthost"
version = "26.10.7"

val userProfile = System.getenv("USERPROFILE") ?: System.getProperty("user.home")
val localProjectBuildRoot = file("$userProfile/ceres-assistant-build - Push & Pull")
layout.buildDirectory.set(localProjectBuildRoot.resolve("jetbrains"))

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local(providers.environmentVariable("WEBSTORM_HOME"))
    }
}

val marketplaceReadme = providers.fileContents(layout.projectDirectory.file("README.md")).asText

intellijPlatform {
    buildSearchableOptions = true
    sandboxContainer.set(localProjectBuildRoot.resolve("sandbox"))

    pluginConfiguration {
        name = providers.fileContents(layout.projectDirectory.file("package.json")).asText.map { json ->
            val metadata = groovy.json.JsonSlurper().parseText(json) as Map<*, *>
            val displayName = metadata["displayName"] as String
            require(displayName.isNotBlank()) { "package.json displayName must not be blank." }
            displayName
        }
        version = project.version.toString()
        description = marketplaceReadme.map { markdown ->
            val document = org.commonmark.parser.Parser.builder().build().parse(markdown)
            org.commonmark.renderer.html.HtmlRenderer.builder().build().render(document)
        }

        ideaVersion {
            sinceBuild = "243"
        }
    }
}

tasks {
    processResources {
        includeEmptyDirs = false
        val projectDirectory = layout.projectDirectory.asFile
        inputs.file(layout.projectDirectory.file(".jbignore"))
        val ignoreRules = providers.fileContents(layout.projectDirectory.file(".jbignore")).asText.get()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()

        eachFile {
            val sourcePath = file.relativeTo(projectDirectory).invariantSeparatorsPath
            var ignored = false
            for (rule in ignoreRules) {
                val included = rule.startsWith("!")
                val pattern = rule.removePrefix("!").removePrefix("/")
                if (org.apache.tools.ant.types.selectors.SelectorUtils.matchPath(pattern, sourcePath, false)) {
                    ignored = !included
                }
            }
            if (ignored) {
                exclude()
            }
        }
    }

    withType<JavaCompile> {
        options.release.set(21)
    }
}
