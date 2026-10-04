package dev.brahmkshatriya.wasmtime.demo.shared

import kotlinx.serialization.Serializable

@Serializable
data class Product(
    val id: Int,
    val title: String,
    val description: String,
    val category: String,
    val price: Double,
    val discountPercentage: Double,
    val rating: Double,
    val stock: Int,
    val brand: String? = null,
    val sku: String,
    val weight: Int,
    val thumbnail: String,
)
