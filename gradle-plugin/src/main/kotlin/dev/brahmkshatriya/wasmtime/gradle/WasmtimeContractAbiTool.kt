@file:OptIn(org.jetbrains.kotlin.library.abi.ExperimentalLibraryAbiReader::class)

package dev.brahmkshatriya.wasmtime.gradle

import java.io.File
import org.jetbrains.kotlin.library.abi.AbiClass
import org.jetbrains.kotlin.library.abi.AbiClassKind
import org.jetbrains.kotlin.library.abi.AbiClassifierReference
import org.jetbrains.kotlin.library.abi.AbiDeclarationContainer
import org.jetbrains.kotlin.library.abi.AbiFunction
import org.jetbrains.kotlin.library.abi.AbiType
import org.jetbrains.kotlin.library.abi.AbiTypeArgument
import org.jetbrains.kotlin.library.abi.AbiTypeNullability
import org.jetbrains.kotlin.library.abi.AbiVariance
import org.jetbrains.kotlin.library.abi.LibraryAbiReader

public object WasmtimeContractAbiToolMain {
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size >= 2) { "usage: WasmtimeContractAbiToolMain <contract-fq-name> <klib>..." }
        val methods = readContract(args[0], args.drop(1).map(::File).toSet())
        methods.forEach { method ->
            println("${method.methodId}\t${method.name}\t${method.returnType}")
        }
    }
}

private data class ToolMethod(
    val name: String,
    val returnType: String,
    val methodId: Int,
)

private fun readContract(contractFqName: String, files: Set<File>): List<ToolMethod> {
    val failures = mutableListOf<String>()
    val contract = files.asSequence().mapNotNull { file ->
        try {
            LibraryAbiReader.readAbiInfo(file)
                .topLevelDeclarations
                .let(::allClasses)
                .firstOrNull { it.qualifiedName.kotlinName() == contractFqName }
        } catch (error: Throwable) {
            failures += "${file.absolutePath}: ${error::class.java.name}: ${error.message}"
            null
        }
    }.firstOrNull() ?: throw IllegalArgumentException(
        "Could not find contract interface $contractFqName. Reader failures: ${failures.joinToString(" | ")}"
    )

    require(contract.kind == AbiClassKind.INTERFACE) {
        "Wasmtime extension contract must be an interface: $contractFqName"
    }
    val functions = contract.declarations.filterIsInstance<AbiFunction>()
        .filterNot(AbiFunction::isConstructor)
    require(functions.isNotEmpty()) {
        "Wasmtime extension contract has no callable methods: $contractFqName"
    }
    val unsupported = functions.filter { !it.isSuspend || it.valueParameters.isNotEmpty() }
    require(unsupported.isEmpty()) {
        val names = unsupported.joinToString { it.qualifiedName.kotlinName() }
        "Automatic Wasmtime contracts currently require zero-argument suspend methods; unsupported: $names"
    }

    val methods = functions.map { function ->
        val name = function.qualifiedName.toString().substringAfterLast('.')
        val returnType = function.returnType?.renderType() ?: "kotlin.Unit"
        val signature = "$contractFqName#$name():$returnType"
        ToolMethod(name, returnType, stableMethodId(signature))
    }.sortedBy(ToolMethod::name)

    val collisions = methods.groupBy(ToolMethod::methodId).filterValues { it.size > 1 }
    require(collisions.isEmpty()) {
        "Wasmtime method-id collision in $contractFqName: $collisions"
    }
    return methods
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
