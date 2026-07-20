plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "com.deploymenthost"
version = "26.7.8"

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

intellijPlatform {
    buildSearchableOptions = true
    sandboxContainer.set(localProjectBuildRoot.resolve("sandbox"))

    pluginConfiguration {
        name = "Push & Pull"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "243"
        }
    }
}

tasks {
    withType<JavaCompile> {
        options.release.set(21)
    }
}
