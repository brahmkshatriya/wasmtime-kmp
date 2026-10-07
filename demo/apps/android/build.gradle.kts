plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "dev.brahmkshatriya.wasmtime.demo"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.brahmkshatriya.wasmtime.demo"
        minSdk = 24
        targetSdk = libs.versions.compileSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }

    sourceSets.getByName("main").jniLibs.srcDir(
        project(":lib").layout.buildDirectory.dir("generated/jniLibs").get().asFile
    )
}

dependencies {
    implementation(projects.demo.client)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.compose.android)
}

tasks.named("preBuild").configure {
    dependsOn(":lib:buildAndroidNativeBridge")
}
