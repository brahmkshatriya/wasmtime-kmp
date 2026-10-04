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

tasks.register("publishWasmtimeNonAppleToMavenRepository") {
    group = "publishing"
    description = "Publish the Wasmtime KMP root, Linux/Windows/Android/JVM/Web targets, guest runtime, and Gradle plugins."
    dependsOn(
        ":lib:publishKotlinMultiplatformPublicationToWasmtimeRepository",
        ":lib:publishAndroidPublicationToWasmtimeRepository",
        ":lib:publishJvmPublicationToWasmtimeRepository",
        ":lib:publishWasmJsPublicationToWasmtimeRepository",
        ":lib:publishLinuxX64PublicationToWasmtimeRepository",
        ":lib:publishLinuxArm64PublicationToWasmtimeRepository",
        ":lib:publishMingwX64PublicationToWasmtimeRepository",
        ":lib:guest-runtime:publishKotlinMultiplatformPublicationToWasmtimeRepository",
        ":lib:guest-runtime:publishWasmWasiPublicationToWasmtimeRepository",
    )
    dependsOn(gradle.includedBuild("gradle-plugin").task(":publishAllPublicationsToWasmtimeRepository"))
}

tasks.register("publishWasmtimeAppleToMavenRepository") {
    group = "publishing"
    description = "Publish the macOS x64/ARM64 and iOS ARM64 Wasmtime KMP target artifacts."
    dependsOn(
        ":lib:publishMacosX64PublicationToWasmtimeRepository",
        ":lib:publishMacosArm64PublicationToWasmtimeRepository",
        ":lib:publishIosArm64PublicationToWasmtimeRepository",
    )
}
