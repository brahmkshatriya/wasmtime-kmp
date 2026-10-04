import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeNative) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidKMPLibrary) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

val wasmtimeMavenRepository = providers.gradleProperty("wasmtimeMavenRepository")
    .orElse(providers.environmentVariable("WASMTIME_MAVEN_REPOSITORY"))
val wasmtimeScmUrl = providers.gradleProperty("wasmtimeScmUrl")
    .orElse("https://github.com/brahmkshatriya/wasmtime-kmp")

subprojects {
    pluginManager.withPlugin("maven-publish") {
        val emptyJavadocJar = tasks.register<Jar>("emptyJavadocJar") {
            archiveClassifier.set("javadoc")
        }

        extensions.configure<PublishingExtension> {
            if (wasmtimeMavenRepository.isPresent) {
                repositories.maven {
                    name = "Wasmtime"
                    url = uri(wasmtimeMavenRepository.get())
                }
            }

            publications.withType<MavenPublication>().configureEach {
                artifact(emptyJavadocJar)
                pom {
                    name.set("Wasmtime KMP ${project.name}")
                    description.set("Kotlin Multiplatform Wasmtime runtime and tooling for ${project.name}.")
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
    }
}

tasks.register("publishWasmtimeCommonToMavenRepository") {
    group = "publishing"
    description = "Publish architecture-neutral KMP/JVM/Web/WASI metadata and Gradle plugins."
    dependsOn(
        ":lib:publishKotlinMultiplatformPublicationToWasmtimeRepository",
        ":lib:publishJvmPublicationToWasmtimeRepository",
        ":lib:publishWasmJsPublicationToWasmtimeRepository",
        ":lib:guest-runtime:publishKotlinMultiplatformPublicationToWasmtimeRepository",
        ":lib:guest-runtime:publishWasmWasiPublicationToWasmtimeRepository",
    )
    dependsOn(gradle.includedBuild("gradle-plugin").task(":publishAllPublicationsToWasmtimeRepository"))
}

tasks.register("publishWasmtimeAndroidToMavenRepository") {
    group = "publishing"
    description = "Publish the Android AAR with both supported Android ABIs."
    dependsOn(":lib:publishAndroidPublicationToWasmtimeRepository")
}

tasks.register("publishWasmtimeLinuxX64ToMavenRepository") {
    group = "publishing"
    description = "Publish Linux x64 and its JVM Linux x64 runtime classifier."
    dependsOn(
        ":lib:publishLinuxX64PublicationToWasmtimeRepository",
        ":lib:copyJvmLinuxX64ClassifierToWasmtimeRepository",
    )
}

tasks.register("publishWasmtimeLinuxArm64ToMavenRepository") {
    group = "publishing"
    description = "Publish Linux ARM64 and its JVM Linux ARM64 runtime classifier."
    dependsOn(
        ":lib:publishLinuxArm64PublicationToWasmtimeRepository",
        ":lib:copyJvmLinuxArm64ClassifierToWasmtimeRepository",
    )
}

tasks.register("publishWasmtimeWindowsX64ToMavenRepository") {
    group = "publishing"
    description = "Publish Windows x64 on a native Windows runner."
    dependsOn(":lib:publishMingwX64PublicationToWasmtimeRepository")
}

tasks.register("publishWasmtimeMacosX64ToMavenRepository") {
    group = "publishing"
    description = "Publish macOS x64 on an Intel macOS runner."
    dependsOn(":lib:publishMacosX64PublicationToWasmtimeRepository")
}

tasks.register("publishWasmtimeMacosArm64ToMavenRepository") {
    group = "publishing"
    description = "Publish macOS ARM64 on an Apple Silicon runner."
    dependsOn(":lib:publishMacosArm64PublicationToWasmtimeRepository")
}

tasks.register("publishWasmtimeIosArm64ToMavenRepository") {
    group = "publishing"
    description = "Publish iOS ARM64 on an Apple Silicon macOS runner."
    dependsOn(":lib:publishIosArm64PublicationToWasmtimeRepository")
}
