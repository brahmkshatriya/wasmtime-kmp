@file:OptIn(org.jetbrains.kotlin.library.abi.ExperimentalLibraryAbiReader::class)

package dev.brahmkshatriya.wasmtime.gradle

import java.io.File
import org.jetbrains.kotlin.library.abi.AbiClass
import org.jetbrains.kotlin.library.abi.AbiClassKind
import org.jetbrains.kotlin.library.abi.AbiClassifierReference
import org.jetbrains.kotlin.library.abi.AbiDeclarationContainer
import org.jetbrains.kotlin.library.abi.AbiFunction
import org.jetbrains.kotlin.library.abi.AbiProperty
import org.jetbrains.kotlin.library.abi.AbiPropertyKind
import org.jetbrains.kotlin.library.abi.AbiType
import org.jetbrains.kotlin.library.abi.AbiTypeArgument
import org.jetbrains.kotlin.library.abi.AbiTypeNullability
import org.jetbrains.kotlin.library.abi.AbiValueParameterKind
import org.jetbrains.kotlin.library.abi.AbiVariance
import org.jetbrains.kotlin.library.abi.LibraryAbiReader

/**
 * Internal command-line entry point used by the Gradle plugin to inspect compiled contract KLIBs.
 *
 * This object is public only because Gradle launches it in an isolated JVM; application code should use the
 * host/extension Gradle DSL rather than invoking it directly.
 */
public object WasmtimeContractAbiToolMain {
    /** Executes the isolated ABI inspection process. Intended for the Gradle plugin only. */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size >= 2) { "usage: WasmtimeContractAbiToolMain <contract-fq-name> <klib>..." }
        val methods = readContract(args[0], args.drop(1).map(::File).toSet())
        methods.forEach { method ->
            println(
                buildList {
                    add(method.methodId.toString())
                    add(method.isSuspend.toString())
                    add(method.kind.name)
                    add(method.name)
                    add(method.returnType)
                    addAll(method.parameterTypes)
                }.joinToString("\t")
            )
        }
    }
}

private data class ToolMethod(
    val name: String,
    val returnType: String,
    val parameterTypes: List<String>,
    val methodId: Int,
    val isSuspend: Boolean,
    val kind: ToolMemberKind,
)

private enum class ToolMemberKind {
    FUNCTION,
    PROPERTY_GETTER,
    PROPERTY_SETTER,
}

private fun readContract(contractFqName: String, files: Set<File>): List<ToolMethod> {
    val failures = mutableListOf<String>()
    val classes = linkedMapOf<String, AbiClass>()
    files.forEach { file ->
        try {
            LibraryAbiReader.readAbiInfo(file)
                .topLevelDeclarations
                .let(::allClasses)
                .forEach { abiClass -> classes.putIfAbsent(abiClass.qualifiedName.kotlinName(), abiClass) }
        } catch (error: Throwable) {
            failures += "${file.absolutePath}: ${error::class.java.name}: ${error.message}"
        }
    }
    val contract = classes[contractFqName] ?: throw IllegalArgumentException(
        "Could not find contract interface $contractFqName. Reader failures: ${failures.joinToString(" | ")}"
    )

    require(contract.kind == AbiClassKind.INTERFACE) {
        "Wasmtime extension contract must be an interface: $contractFqName"
    }
    val hierarchy = contractInterfaceHierarchy(contract, classes)
    val functions = hierarchy.flatMap { it.declarations.filterIsInstance<AbiFunction>() }
        .filterNot(AbiFunction::isConstructor)
        .distinctBy { function ->
            val parameters = function.valueParameters.joinToString(",") { it.type.toString() }
            "${function.qualifiedName.toString().substringAfterLast('.')}($parameters)"
        }
    val properties = hierarchy.flatMap { it.declarations.filterIsInstance<AbiProperty>() }
        .distinctBy { it.qualifiedName.toString().substringAfterLast('.') }
    require(functions.isNotEmpty() || properties.isNotEmpty()) {
        "Wasmtime extension contract has no callable members: $contractFqName"
    }
    val unsupported = functions.filter { function ->
        function.valueParameters.any { it.kind == AbiValueParameterKind.EXTENSION_RECEIVER } ||
            function.valueParameters.any { it.kind == AbiValueParameterKind.CONTEXT } ||
            function.valueParameters.any { it.isVararg }
    }
    require(unsupported.isEmpty()) {
        val names = unsupported.joinToString { it.qualifiedName.kotlinName() }
        "Automatic Wasmtime contracts require ordinary value parameters; extension receivers, context parameters, and varargs are unsupported: $names"
    }

    val functionMethods = functions.map { function ->
        val name = function.qualifiedName.toString().substringAfterLast('.')
        val returnType = function.returnType?.renderType() ?: "kotlin.Unit"
        val parameterTypes = function.valueParameters.map { it.type.renderType() }
        val signature = "$contractFqName#$name(${parameterTypes.joinToString(",")}):$returnType"
        ToolMethod(
            name,
            returnType,
            parameterTypes,
            stableMethodId(signature),
            function.isSuspend,
            ToolMemberKind.FUNCTION,
        )
    }
    val propertyMethods = properties.flatMap { property ->
        val name = property.qualifiedName.toString().substringAfterLast('.')
        val getter = property.getter
            ?: throw IllegalArgumentException("Wasmtime contract property has no getter: $name")
        val type = getter.returnType?.renderType()
            ?: throw IllegalArgumentException("Wasmtime contract property has no getter type: $name")
        buildList {
            val getterSignature = "$contractFqName#get-$name():$type"
            add(
                ToolMethod(
                    name,
                    type,
                    emptyList(),
                    stableMethodId(getterSignature),
                    false,
                    ToolMemberKind.PROPERTY_GETTER,
                )
            )
            if (property.kind == AbiPropertyKind.VAR) {
                val setter = property.setter
                    ?: throw IllegalArgumentException("Wasmtime mutable contract property has no setter: $name")
                val parameterTypes = setter.valueParameters.map { it.type.renderType() }
                require(parameterTypes.size == 1) { "Wasmtime property setter must have exactly one value: $name" }
                val setterSignature = "$contractFqName#set-$name(${parameterTypes.single()}):kotlin.Unit"
                add(
                    ToolMethod(
                        name,
                        "kotlin.Unit",
                        parameterTypes,
                        stableMethodId(setterSignature),
                        false,
                        ToolMemberKind.PROPERTY_SETTER,
                    )
                )
            }
        }
    }
    val methods = (functionMethods + propertyMethods).sortedWith(
        compareBy<ToolMethod>(ToolMethod::name).thenBy { it.kind.ordinal }
    )

    val reservedMethodIds = setOf(Int.MIN_VALUE, Int.MIN_VALUE + 1, Int.MIN_VALUE + 2)
    require(methods.none { it.methodId in reservedMethodIds }) {
        "Wasmtime contract method ID collides with a reserved runtime method ID"
    }

    val collisions = methods.groupBy(ToolMethod::methodId).filterValues { it.size > 1 }
    require(collisions.isEmpty()) {
        "Wasmtime method-id collision in $contractFqName: $collisions"
    }
    return methods
}

private fun contractInterfaceHierarchy(
    root: AbiClass,
    classes: Map<String, AbiClass>,
): List<AbiClass> {
    val result = mutableListOf<AbiClass>()
    val seen = mutableSetOf<String>()

    fun visit(current: AbiClass) {
        val currentName = current.qualifiedName.kotlinName()
        if (!seen.add(currentName)) return
        result += current
        current.superTypes.forEach { superType ->
            val simple = superType as? AbiType.Simple
                ?: throw IllegalArgumentException("Unsupported Wasmtime contract supertype: $superType")
            val reference = simple.classifierReference as? AbiClassifierReference.ClassReference
                ?: throw IllegalArgumentException("Unsupported Wasmtime contract supertype reference: ${simple.classifierReference}")
            val superName = reference.className.kotlinName()
            if (superName == "kotlin.Any") return@forEach
            require(simple.arguments.isEmpty()) {
                "Generic superinterfaces are not supported in generated Wasmtime contracts: $superName"
            }
            val parent = classes[superName]
                ?: throw IllegalArgumentException("Could not resolve Wasmtime contract superinterface $superName")
            require(parent.kind == AbiClassKind.INTERFACE) {
                "Wasmtime contract supertype must be an interface: $superName"
            }
            visit(parent)
        }
    }

    visit(root)
    return result
}

private fun allClasses(container: AbiDeclarationContainer): Sequence<AbiClass> = sequence {
    for (declaration in container.declarations) {
        if (declaration is AbiClass) {
            yield(declaration)
            yieldAll(allClasses(declaration))
        }
    }
}

private fun org.jetbrains.kotlin.library.abi.AbiQualifiedName.kotlinName(): String =
    toString().replace('/', '.')

private fun AbiType.renderType(): String = when (this) {
    is AbiType.Simple -> {
        val base = when (val reference = classifierReference) {
            is AbiClassifierReference.ClassReference -> reference.className.kotlinName()
            is AbiClassifierReference.TypeParameterReference ->
                throw IllegalArgumentException(
                    "Generic type parameters are not supported in generated Wasmtime contracts: ${reference.tag}"
                )
        }
        val renderedArguments = arguments.takeIf { it.isNotEmpty() }
            ?.joinToString(", ", "<", ">") { argument ->
                when (argument) {
                    is AbiTypeArgument.StarProjection -> "*"
                    is AbiTypeArgument.TypeProjection -> {
                        val prefix = when (argument.variance) {
                            AbiVariance.INVARIANT -> ""
                            AbiVariance.IN -> "in "
                            AbiVariance.OUT -> "out "
                        }
                        prefix + argument.type.renderType()
                    }
                }
            }.orEmpty()
        val nullable = if (nullability == AbiTypeNullability.MARKED_NULLABLE) "?" else ""
        base + renderedArguments + nullable
    }
    else -> throw IllegalArgumentException("Unsupported Wasmtime ABI type: $this")
}

private fun stableMethodId(signature: String): Int {
    var hash = 0x811c9dc5u
    for (byte in signature.encodeToByteArray()) {
        hash = hash xor byte.toUByte().toUInt()
        hash *= 0x01000193u
    }
    return hash.toInt()
}
