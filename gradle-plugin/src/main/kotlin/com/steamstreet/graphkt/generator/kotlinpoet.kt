package com.steamstreet.graphkt.generator

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec

/**
 * Add a suppress annotation to a file.
 */
fun FileSpec.Builder.suppress(vararg value: String) {
    addAnnotation(AnnotationSpec.builder(ClassName("kotlin", "Suppress"))
            .addMember(value.joinToString { "\"$it\"" })
            .build()
    )
}

