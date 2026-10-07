plugins {
    id("dev.brahmkshatriya.wasmtime.extension")
}

group = property("GROUP").toString()

wasmtime {
    extensionApi("demo") {
        add(projects.demo.shared)
    }
}

wasmtimeExtension {
    contractInterface.set("dev.brahmkshatriya.wasmtime.demo.shared.MusicExtensionClient")
    implementationClass.set("dev.brahmkshatriya.wasmtime.demo.plugin1.MusicPlugin")
    displayName.set("Echo-style Music 1")
    capabilities("Http", "PersistentStorage", "Credentials", "Logging", "Streaming", "HostResources")
    compileOnlyDependencies {
        useExtensionApi("demo")
    }
}
