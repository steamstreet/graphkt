package com.steamstreet.graphkt.generator.schema

/** Provides deterministic interface and union relationships for code emitters. */
internal class SchemaRelationships(schema: SchemaModel) {
    private val typesByName = schema.types.associateBy { it.name }
    private val objects = schema.types.filterIsInstance<ObjectType>()
    private val unions = schema.types.filterIsInstance<UnionType>()

    fun possibleTypes(type: SchemaType): List<ObjectType> = when (type) {
        is InterfaceType -> objects.filter { objectType ->
            interfacesOf(objectType).any { it.name == type.name }
        }.sortedBy { it.name }

        is UnionType -> type.members.map { member ->
            typesByName[member] as? ObjectType
                ?: error("Union member is not an object type: $member")
        }.sortedBy { it.name }

        else -> emptyList()
    }

    fun interfacesOf(type: ObjectType): List<InterfaceType> =
        collectInterfaces(type.interfaces)

    fun interfacesOf(type: InterfaceType): List<InterfaceType> =
        collectInterfaces(type.interfaces)

    fun unionsOf(type: ObjectType): List<UnionType> = unions
        .filter { type.name in it.members }
        .sortedBy { it.name }

    fun inheritedFieldNames(type: ObjectType): Set<String> =
        interfacesOf(type).flatMapTo(linkedSetOf()) { it.fields.map(Field::name) }

    fun inheritedFieldNames(type: InterfaceType): Set<String> =
        interfacesOf(type).flatMapTo(linkedSetOf()) { it.fields.map(Field::name) }

    private fun collectInterfaces(names: List<String>): List<InterfaceType> {
        val interfaces = LinkedHashMap<String, InterfaceType>()

        fun collect(name: String) {
            if (name in interfaces) return

            val interfaceType = typesByName[name] as? InterfaceType
                ?: error("Implemented type is not an interface: $name")
            interfaces[name] = interfaceType
            interfaceType.interfaces.forEach(::collect)
        }

        names.sorted().forEach(::collect)
        return interfaces.values.toList()
    }
}
