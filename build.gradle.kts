plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.24"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.plugin.notes"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Markdown parser or common utilities if needed
    implementation("org.commonmark:commonmark:0.21.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.21.0")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.21.0")
}

intellij {
    version.set("2024.1.4")
    type.set("IC")
    plugins.set(listOf())
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set(provider { null })
    }

    buildSearchableOptions {
        enabled = false
    }

    buildPlugin {
        doLast {
            val releaseDir = rootDir.parentFile.resolve("release")
            releaseDir.mkdirs()
            archiveFile.orNull?.asFile?.let { zipFile ->
                val targetFile = releaseDir.resolve(zipFile.name)
                zipFile.copyTo(targetFile, overwrite = true)
                logger.lifecycle("📦 Plugin distribué automatiquement dans : ${targetFile.absolutePath}")
            }
        }
    }
}
