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
    entryPointAnnotation.set("dev.brahmkshatriya.wasmtime.demo.shared.ExtensionEntry")
    compileOnlyDependencies {
        useExtensionApi("demo")
    }
}
