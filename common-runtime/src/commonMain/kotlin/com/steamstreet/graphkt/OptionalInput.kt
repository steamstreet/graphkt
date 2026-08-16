package com.steamstreet.graphkt

/** Preserves the difference between an omitted GraphQL input and an explicit value. */
public sealed interface OptionalInput<out Value> {
    public data object Absent : OptionalInput<Nothing>
    public data class Present<Value>(public val value: Value) : OptionalInput<Value>
}

public fun <Value> Value.asOptionalInput(): OptionalInput<Value> = OptionalInput.Present(this)
