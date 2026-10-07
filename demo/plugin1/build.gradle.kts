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
    contractInterface.set("dev.brahmkshatriya.wasmtime.demo.shared.Plugin")
    implementationClass.set("dev.brahmkshatriya.wasmtime.demo.plugin1.ProductPlugin")
    displayName.set("Product Plugin 1")
    capabilities("Http", "PersistentStorage", "Credentials", "Logging", "Streaming")
    compileOnlyDependencies {
        useExtensionApi("demo")
    }
}
