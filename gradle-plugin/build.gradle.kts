import org.gradle.api.publish.maven.MavenPublication

plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
}

group = "dev.brahmkshatriya.wasmtime"
version = providers.gradleProperty("VERSION")
    .orElse(providers.environmentVariable("WASMTIME_KMP_VERSION"))
    .orElse("unspecified")
    .get()

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.20")
}

java {
    withSourcesJar()
    withJavadocJar()
}

gradlePlugin {
    plugins {
        create("wasmtimeHost") {
            id = "dev.brahmkshatriya.wasmtime.host"
            implementationClass = "dev.brahmkshatriya.wasmtime.gradle.WasmtimeHostPlugin"
            displayName = "Wasmtime KMP Host Runtime"
            description = "Builds an open-world Kotlin/Wasm runtime bundle for Wasmtime extensions."
        }
        create("wasmtimeExtension") {
            id = "dev.brahmkshatriya.wasmtime.extension"
            implementationClass = "dev.brahmkshatriya.wasmtime.gradle.WasmtimeExtensionPlugin"
            displayName = "Wasmtime KMP Extension"
            description = "Exports tiny open-world Kotlin/Wasm extensions with metadata and GC type pruning."
        }
    }
}

val wasmtimeMavenRepository = providers.gradleProperty("wasmtimeMavenRepository")
    .orElse(providers.environmentVariable("WASMTIME_MAVEN_REPOSITORY"))
val wasmtimeScmUrl = providers.gradleProperty("wasmtimeScmUrl")
    .orElse("https://github.com/brahmkshatriya/wasmtime-kmp")

publishing {
    if (wasmtimeMavenRepository.isPresent) {
        repositories.maven {
            name = "Wasmtime"
            url = uri(wasmtimeMavenRepository.get())
        }
    }
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Wasmtime KMP Gradle Plugin")
            description.set("Gradle plugins for Wasmtime KMP host runtimes and Wasm/WASI extensions.")
            url.set(wasmtimeScmUrl)
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("brahmkshatriya")
                    name.set("Shivam Brahmkshatriya")
                    url.set("https://github.com/brahmkshatriya")
                }
            }
            scm {
                val scmUrl = wasmtimeScmUrl.get()
                val scmGitUrl = if (scmUrl.endsWith(".git")) scmUrl else "$scmUrl.git"
                url.set(scmGitUrl)
                connection.set("scm:git:$scmGitUrl")
                developerConnection.set("scm:git:$scmGitUrl")
            }
        }
    }
}

tasks.jar {
    manifest.attributes["Implementation-Version"] = project.version
}
