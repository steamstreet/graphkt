package com.steamstreet.graphkt.server.execution

import com.steamstreet.graphkt.GraphQLError
import com.steamstreet.graphkt.Location
import com.steamstreet.graphkt.OptionalInput

internal data class DocumentValidationResult(
    val operation: OperationDefinition?,
    val errors: List<GraphQLError>,
) {
    val isValid: Boolean get() = errors.isEmpty() && operation != null
}

internal class GraphQLDocumentValidator(
    private val schema: GraphQLSchemaDefinition,
    private val limits: GraphQLDocumentLimits = GraphQLDocumentLimits(),
) {
    fun validate(document: ExecutableDocument, operationName: String?): DocumentValidationResult {
        val errors = mutableListOf<GraphQLError>()
        val fragments = document.fragments.groupBy { it.name }
        val fragmentIndex = fragments.mapValues { it.value.first() }

        validateOperationNames(document, errors)
        validateFragmentNames(fragments, errors)
        validateFragmentDeclarations(document.fragments, errors)
        validateFragmentCycles(fragmentIndex, errors)

        document.operations.forEach { operation ->
            validateOperation(operation, fragmentIndex, errors)
        }
        document.fragments.forEach { fragment ->
            validateSelectionSet(fragment.typeCondition, fragment.selections, fragmentIndex, errors)
        }

        val usedFragments = mutableSetOf<String>()
        document.operations.forEach { operation ->
            validateOperationVariables(operation, fragmentIndex, usedFragments, errors)
        }
        document.fragments.filter { it.name !in usedFragments }.forEach { fragment ->
            errors += error("Fragment '${fragment.name}' is never used", fragment.location)
        }

        val operation = try {
            document.selectOperation(operationName)
        } catch (exception: GraphQLDocumentException) {
            errors += error(exception.message ?: "The operation could not be selected", exception.location)
            null
        }

        return DocumentValidationResult(operation, errors.distinct())
    }

    private fun validateOperationNames(document: ExecutableDocument, errors: MutableList<GraphQLError>) {
        document.operations.filter { it.name != null }
            .groupBy { it.name }
            .filterValues { it.size > 1 }
            .forEach { (name, operations) ->
                operations.forEach { operation ->
                    errors += error("Operation name '$name' is not unique", operation.location)
                }
            }

        if (document.operations.size > 1) {
            document.operations.filter { it.name == null }.forEach { operation ->
                errors += error("An anonymous operation must be the only operation in a document", operation.location)
            }
        }
    }

    private fun validateFragmentNames(
        fragments: Map<String, List<FragmentDefinition>>,
        errors: MutableList<GraphQLError>,
    ) {
        fragments.filterValues { it.size > 1 }.forEach { (name, definitions) ->
            definitions.forEach { fragment ->
                errors += error("Fragment name '$name' is not unique", fragment.location)
            }
        }
    }

    private fun validateFragmentDeclarations(
        fragments: List<FragmentDefinition>,
        errors: MutableList<GraphQLError>,
    ) {
        fragments.forEach { fragment ->
            val target = schema.type(fragment.typeCondition)
            when {
                target == null -> errors += error(
                    "Fragment '${fragment.name}' uses unknown type '${fragment.typeCondition}'",
                    fragment.location,
                )

                !target.isCompositeType() -> errors += error(
                    "Fragment '${fragment.name}' must target an object, interface, or union",
                    fragment.location,
                )
            }
            validateDirectives(fragment.directives, GraphQLDirectiveLocation.FRAGMENT_DEFINITION, errors)
        }
    }

    private fun validateOperation(
        operation: OperationDefinition,
        fragments: Map<String, FragmentDefinition>,
        errors: MutableList<GraphQLError>,
    ) {
        val root = schema.rootType(operation.type)
        if (root == null) {
            errors += error(
                "The schema does not define a ${operation.type.name.lowercase()} root type",
                operation.location,
            )
            return
        }

        validateDirectives(operation.directives, operation.type.directiveLocation(), errors)
        validateVariableDefinitions(operation.variables, errors)
        if (operation.type == OperationType.SUBSCRIPTION) {
            validateSubscriptionRootFields(operation, fragments, errors)
        }
        GraphQLFieldMergingValidator(schema, fragments).validate(root.name, operation.selections, errors)
        validateSelectionSet(root.name, operation.selections, fragments, errors)
    }

    private fun validateSubscriptionRootFields(
        operation: OperationDefinition,
        fragments: Map<String, FragmentDefinition>,
        errors: MutableList<GraphQLError>,
    ) {
        val rootFields = linkedMapOf<String, FieldSelection>()
        val visitedFragments = mutableSetOf<String>()

        fun collect(selections: List<Selection>) {
            selections.forEach { selection ->
                val directives = when (selection) {
                    is FieldSelection -> selection.directives
                    is FragmentSpread -> selection.directives
                    is InlineFragment -> selection.directives
                }
                directives
                    .filter { directive -> directive.name == "skip" || directive.name == "include" }
                    .forEach { directive ->
                        errors += error(
                            "Subscription root selections must not use '@${directive.name}'",
                            directive.location,
                        )
                    }

                when (selection) {
                    is FieldSelection -> if (selection.responseName !in rootFields) {
                        rootFields[selection.responseName] = selection
                    }
                    is FragmentSpread -> if (visitedFragments.add(selection.name)) {
                        fragments[selection.name]?.let { fragment -> collect(fragment.selections) }
                    }

                    is InlineFragment -> collect(selection.selections)
                }
            }
        }

        collect(operation.selections)
        if (rootFields.size != 1) {
            errors += error("A subscription must select exactly one root field", operation.location)
        } else if (rootFields.values.single().name.startsWith("__")) {
            errors += error("A subscription root field must not be an introspection field", operation.location)
        }
    }

    private fun validateVariableDefinitions(
        variables: List<VariableDefinition>,
        errors: MutableList<GraphQLError>,
    ) {
        variables.groupBy { it.name }.filterValues { it.size > 1 }.forEach { (name, definitions) ->
            definitions.forEach { definition ->
                errors += error("Variable '$$name' is defined more than once", definition.location)
            }
        }

        variables.forEach { variable ->
            val type = variable.type.toSchemaTypeRef()
            val namedType = schema.type(type.namedType().name)
            when {
                namedType == null -> errors += error(
                    "Variable '$${variable.name}' uses unknown type '${type.namedType().name}'",
                    variable.location,
                )

                !namedType.isInputType() -> errors += error(
                    "Variable '$${variable.name}' must use an input type",
                    variable.location,
                )

                variable.defaultValue != null -> validateLiteral(
                    variable.defaultValue,
                    type,
                    variable.location,
                    errors,
                )
            }
            validateDirectives(variable.directives, GraphQLDirectiveLocation.VARIABLE_DEFINITION, errors)
        }
    }

    private fun validateSelectionSet(
        parentTypeName: String,
        selections: List<Selection>,
        fragments: Map<String, FragmentDefinition>,
        errors: MutableList<GraphQLError>,
    ) {
        val parentType = schema.type(parentTypeName)
        if (parentType == null || !parentType.isCompositeType()) return

        selections.forEach { selection ->
            when (selection) {
                is FieldSelection -> validateField(parentType, selection, fragments, errors)
                is FragmentSpread -> {
                    validateDirectives(selection.directives, GraphQLDirectiveLocation.FRAGMENT_SPREAD, errors)
                    val fragment = fragments[selection.name]
                    if (fragment == null) {
                        errors += error("Fragment '${selection.name}' is not defined", selection.location)
                    } else if (!typesOverlap(parentType.name, fragment.typeCondition)) {
                        errors += error(
                            "Fragment '${selection.name}' cannot apply to type '${parentType.name}'",
                            selection.location,
                        )
                    }
                }

                is InlineFragment -> {
                    validateDirectives(selection.directives, GraphQLDirectiveLocation.INLINE_FRAGMENT, errors)
                    val targetName = selection.typeCondition ?: parentType.name
                    val target = schema.type(targetName)
                    when {
                        target == null -> errors += error("Inline fragment uses unknown type '$targetName'", selection.location)
                        !target.isCompositeType() -> errors += error(
                            "Inline fragment must target an object, interface, or union",
                            selection.location,
                        )

                        !typesOverlap(parentType.name, targetName) -> errors += error(
                            "Inline fragment on '$targetName' cannot apply to type '${parentType.name}'",
                            selection.location,
                        )

                        else -> validateSelectionSet(targetName, selection.selections, fragments, errors)
                    }
                }
            }
        }
    }

    private fun validateField(
        parentType: GraphQLNamedType,
        selection: FieldSelection,
        fragments: Map<String, FragmentDefinition>,
        errors: MutableList<GraphQLError>,
    ) {
        validateDirectives(selection.directives, GraphQLDirectiveLocation.FIELD, errors)
        val field = if (selection.name == "__typename") {
            GraphQLFieldDefinition("__typename", GraphQLTypeRef.NonNull(GraphQLTypeRef.Named("String")))
        } else {
            when (parentType) {
                is GraphQLObjectType -> parentType.field(selection.name)
                is GraphQLInterfaceType -> parentType.field(selection.name)
                else -> null
            }
        }

        if (field == null) {
            errors += error("Field '${selection.name}' does not exist on type '${parentType.name}'", selection.location)
            return
        }

        validateArguments(field, selection.arguments, errors)
        val resultType = schema.type(field.type.namedType().name)
        when {
            resultType == null -> errors += error(
                "Field '${parentType.name}.${field.name}' uses unknown type '${field.type.namedType().name}'",
                selection.location,
            )

            resultType.isLeafType() && selection.selections.isNotEmpty() -> errors += error(
                "Leaf field '${selection.responseName}' must not have a selection set",
                selection.location,
            )

            resultType.isCompositeType() && selection.selections.isEmpty() -> errors += error(
                "Field '${selection.responseName}' must have a selection set",
                selection.location,
            )

            resultType.isCompositeType() -> validateSelectionSet(
                resultType.name,
                selection.selections,
                fragments,
                errors,
            )
        }
    }

    private fun validateArguments(
        field: GraphQLFieldDefinition,
        arguments: List<Argument>,
        errors: MutableList<GraphQLError>,
    ) {
        arguments.groupBy { it.name }.filterValues { it.size > 1 }.forEach { (name, duplicates) ->
            duplicates.forEach { argument ->
                errors += error("Argument '$name' is provided more than once", argument.location)
            }
        }

        arguments.forEach { argument ->
            val definition = field.argument(argument.name)
            if (definition == null) {
                errors += error("Argument '${argument.name}' is not defined on field '${field.name}'", argument.location)
            } else {
                validateLiteral(argument.value, definition.type, argument.location, errors)
            }
        }

        field.arguments.filter { it.isRequired() && arguments.none { argument -> argument.name == it.name } }
            .forEach { definition ->
                errors += error("Required argument '${definition.name}' is missing", null)
            }
    }

    private fun validateLiteral(
        value: Value,
        expectedType: GraphQLTypeRef,
        location: SourceLocation?,
        errors: MutableList<GraphQLError>,
    ) {
        if (value is Value.Variable) return
        if (value is Value.NullValue) {
            if (expectedType is GraphQLTypeRef.NonNull) {
                errors += error("Null is not valid for type '${expectedType.render()}'", location)
            }
            return
        }

        if (expectedType is GraphQLTypeRef.NonNull) {
            validateLiteral(value, expectedType.value, location, errors)
            return
        }

        if (expectedType is GraphQLTypeRef.ListType) {
            if (value is Value.ListValue) {
                value.values.forEach { item -> validateLiteral(item, expectedType.element, location, errors) }
            } else {
                validateLiteral(value, expectedType.element, location, errors)
            }
            return
        }

        val named = expectedType as GraphQLTypeRef.Named
        when (val definition = schema.type(named.name)) {
            is GraphQLScalarType -> if (!value.matchesScalar(definition.name)) {
                errors += error("Value is not valid for scalar '${definition.name}'", location)
            }

            is GraphQLEnumType -> {
                val enumValue = value as? Value.EnumValue
                if (enumValue == null || enumValue.value !in definition.values) {
                    errors += error("Value is not valid for enum '${definition.name}'", location)
                }
            }

            is GraphQLInputObjectType -> validateInputObjectLiteral(value, definition, location, errors)
            null -> errors += error("Input type '${named.name}' is not defined", location)
            else -> errors += error("Type '${named.name}' cannot be used as input", location)
        }
    }

    private fun validateInputObjectLiteral(
        value: Value,
        type: GraphQLInputObjectType,
        location: SourceLocation?,
        errors: MutableList<GraphQLError>,
    ) {
        val objectValue = value as? Value.ObjectValue
        if (objectValue == null) {
            errors += error("Value must be an input object of type '${type.name}'", location)
            return
        }

        objectValue.fields.groupBy { it.name }.filterValues { it.size > 1 }.forEach { (name, duplicates) ->
            duplicates.forEach { field -> errors += error("Input field '$name' is provided more than once", field.location) }
        }
        objectValue.fields.forEach { field ->
            val definition = type.field(field.name)
            if (definition == null) {
                errors += error("Input field '${field.name}' is not defined on '${type.name}'", field.location)
            } else {
                validateLiteral(field.value, definition.type, field.location, errors)
            }
        }
        type.fields.filter { it.isRequired() && objectValue.fields.none { field -> field.name == it.name } }
            .forEach { field -> errors += error("Required input field '${type.name}.${field.name}' is missing", location) }

        if (type.isOneOf) {
            if (objectValue.fields.size != 1) {
                errors += error("OneOf input '${type.name}' requires exactly one field", location)
            } else if (objectValue.fields.single().value is Value.NullValue) {
                errors += error("OneOf input '${type.name}' requires a non-null field", location)
            }
        }
    }

    private fun validateDirectives(
        directives: List<Directive>,
        location: GraphQLDirectiveLocation,
        errors: MutableList<GraphQLError>,
    ) {
        directives.groupBy { it.name }.filter { (name, uses) ->
            uses.size > 1 && schema.directive(name)?.repeatable != true
        }.forEach { (name, duplicates) ->
            duplicates.forEach { directive ->
                errors += error("Directive '@$name' is not repeatable", directive.location)
            }
        }

        directives.forEach { directive ->
            val definition = schema.directive(directive.name)
            if (definition == null) {
                errors += error("Directive '@${directive.name}' is not defined", directive.location)
                return@forEach
            }
            if (location !in definition.locations) {
                errors += error("Directive '@${directive.name}' is not valid at ${location.description}", directive.location)
            }
            validateDirectiveArguments(directive, definition, errors)
        }
    }

    private fun validateDirectiveArguments(
        directive: Directive,
        definition: GraphQLDirectiveDefinition,
        errors: MutableList<GraphQLError>,
    ) {
        directive.arguments.groupBy { it.name }.filterValues { it.size > 1 }.forEach { (name, duplicates) ->
            duplicates.forEach { argument ->
                errors += error("Argument '$name' is provided more than once on '@${directive.name}'", argument.location)
            }
        }
        directive.arguments.forEach { argument ->
            val argumentDefinition = definition.argument(argument.name)
            if (argumentDefinition == null) {
                errors += error("Argument '${argument.name}' is not defined on '@${directive.name}'", argument.location)
            } else {
                validateLiteral(argument.value, argumentDefinition.type, argument.location, errors)
            }
        }
        definition.arguments
            .filter { it.isRequired() && directive.arguments.none { argument -> argument.name == it.name } }
            .forEach { argument ->
                errors += error("Required argument '${argument.name}' is missing on '@${directive.name}'", directive.location)
            }
    }

    private fun validateFragmentCycles(
        fragments: Map<String, FragmentDefinition>,
        errors: MutableList<GraphQLError>,
    ) {
        val visited = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()

        fun visit(name: String, depth: Int) {
            if (name in visited) return
            val fragment = fragments[name] ?: return
            if (depth > limits.maxFragmentNesting) {
                errors += error(
                    "Document exceeds the fragment-nesting limit of ${limits.maxFragmentNesting}",
                    fragment.location,
                )
                return
            }
            visiting += name
            fragment.selections.fragmentSpreads().forEach { spread ->
                when (spread.name) {
                    in visiting -> errors += error(
                        "Fragment cycle includes '${spread.name}'",
                        spread.location,
                    )

                    else -> visit(spread.name, depth + 1)
                }
            }
            visiting -= name
            visited += name
        }

        fragments.keys.forEach { visit(it, 1) }
    }

    private fun validateOperationVariables(
        operation: OperationDefinition,
        fragments: Map<String, FragmentDefinition>,
        usedFragments: MutableSet<String>,
        errors: MutableList<GraphQLError>,
    ) {
        val usages = mutableListOf<VariableUsage>()
        val metrics = OperationMetrics(limits, errors)
        val root = schema.rootType(operation.type) ?: return
        collectSelectionFacts(
            parentTypeName = root.name,
            selections = operation.selections,
            fragments = fragments,
            fragmentStack = mutableSetOf(),
            usedFragments = usedFragments,
            usages = usages,
            metrics = metrics,
            depth = 1,
        )
        operation.directives.forEach { directive -> collectDirectiveVariables(directive, usages) }
        operation.variables.forEach { variable ->
            variable.directives.forEach { directive -> collectDirectiveVariables(directive, usages) }
        }

        val definitions = operation.variables.groupBy { it.name }.mapValues { it.value.first() }
        usages.forEach { usage ->
            val definition = definitions[usage.name]
            if (definition == null) {
                errors += error("Variable '$${usage.name}' is not defined by this operation", usage.location)
                return@forEach
            }

            val variableType = definition.type.toSchemaTypeRef()
            val allowed = isVariableUsageAllowed(
                variableType = variableType,
                variableDefault = definition.defaultValue,
                locationType = usage.expectedType,
                hasLocationDefault = usage.hasLocationDefault,
                requiresNonNull = usage.requiresNonNull,
            )
            if (!allowed) {
                errors += error(
                    "Variable '$${usage.name}' of type '${variableType.render()}' cannot be used where " +
                        "'${usage.expectedType.render()}' is required",
                    usage.location,
                )
            }
        }

        definitions.values.filter { definition -> usages.none { it.name == definition.name } }.forEach { definition ->
            errors += error("Variable '$${definition.name}' is never used", definition.location)
        }
    }

    private fun collectSelectionFacts(
        parentTypeName: String,
        selections: List<Selection>,
        fragments: Map<String, FragmentDefinition>,
        fragmentStack: MutableSet<String>,
        usedFragments: MutableSet<String>,
        usages: MutableList<VariableUsage>,
        metrics: OperationMetrics,
        depth: Int,
    ) {
        if (!metrics.depth(depth)) return
        val parentType = schema.type(parentTypeName)
        selections.forEach { selection ->
            if (metrics.exceeded) return
            when (selection) {
                is FieldSelection -> {
                    metrics.field(selection.alias != null, selection.location)
                    selection.directives.forEach { collectDirectiveVariables(it, usages) }
                    val field = if (selection.name == "__typename") {
                        GraphQLFieldDefinition("__typename", GraphQLTypeRef.NonNull(GraphQLTypeRef.Named("String")))
                    } else {
                        when (parentType) {
                            is GraphQLObjectType -> parentType.field(selection.name)
                            is GraphQLInterfaceType -> parentType.field(selection.name)
                            else -> null
                        }
                    } ?: return@forEach
                    selection.arguments.forEach { argument ->
                        field.argument(argument.name)?.let { definition ->
                            collectValueVariables(
                                argument.value,
                                definition.type,
                                definition.defaultValue is OptionalInput.Present,
                                false,
                                argument.location,
                                usages,
                            )
                        }
                    }
                    if (selection.selections.isNotEmpty()) {
                        collectSelectionFacts(
                            field.type.namedType().name,
                            selection.selections,
                            fragments,
                            fragmentStack,
                            usedFragments,
                            usages,
                            metrics,
                            depth + 1,
                        )
                    }
                }

                is FragmentSpread -> {
                    selection.directives.forEach { collectDirectiveVariables(it, usages) }
                    usedFragments += selection.name
                    metrics.fragment(selection.location)
                    val fragment = fragments[selection.name]
                    if (fragment != null && fragmentStack.add(fragment.name)) {
                        if (!metrics.fragmentDepth(fragmentStack.size, selection.location)) {
                            fragmentStack -= fragment.name
                            return@forEach
                        }
                        fragment.directives.forEach { collectDirectiveVariables(it, usages) }
                        collectSelectionFacts(
                            fragment.typeCondition,
                            fragment.selections,
                            fragments,
                            fragmentStack,
                            usedFragments,
                            usages,
                            metrics,
                            depth,
                        )
                        fragmentStack -= fragment.name
                    }
                }

                is InlineFragment -> {
                    selection.directives.forEach { collectDirectiveVariables(it, usages) }
                    collectSelectionFacts(
                        selection.typeCondition ?: parentTypeName,
                        selection.selections,
                        fragments,
                        fragmentStack,
                        usedFragments,
                        usages,
                        metrics,
                        depth,
                    )
                }
            }
        }
    }

    private fun collectDirectiveVariables(directive: Directive, usages: MutableList<VariableUsage>) {
        val definition = schema.directive(directive.name) ?: return
        directive.arguments.forEach { argument ->
            definition.argument(argument.name)?.let { argumentDefinition ->
                collectValueVariables(
                    value = argument.value,
                    expectedType = argumentDefinition.type,
                    hasLocationDefault = argumentDefinition.defaultValue is OptionalInput.Present,
                    requiresNonNull = false,
                    location = argument.location,
                    usages = usages,
                )
            }
        }
    }

    private fun collectValueVariables(
        value: Value,
        expectedType: GraphQLTypeRef,
        hasLocationDefault: Boolean,
        requiresNonNull: Boolean,
        location: SourceLocation,
        usages: MutableList<VariableUsage>,
    ) {
        if (value is Value.Variable) {
            usages += VariableUsage(value.name, expectedType, hasLocationDefault, requiresNonNull, location)
            return
        }
        val nullableType = if (expectedType is GraphQLTypeRef.NonNull) expectedType.value else expectedType
        when {
            nullableType is GraphQLTypeRef.ListType && value is Value.ListValue -> value.values.forEach { item ->
                collectValueVariables(item, nullableType.element, false, false, location, usages)
            }

            nullableType is GraphQLTypeRef.ListType -> collectValueVariables(
                value,
                nullableType.element,
                false,
                false,
                location,
                usages,
            )

            nullableType is GraphQLTypeRef.Named && value is Value.ObjectValue -> {
                val inputObject = schema.type(nullableType.name) as? GraphQLInputObjectType ?: return
                value.fields.forEach { objectField ->
                    inputObject.field(objectField.name)?.let { field ->
                        collectValueVariables(
                            objectField.value,
                            field.type,
                            field.defaultValue is OptionalInput.Present,
                            inputObject.isOneOf,
                            objectField.location,
                            usages,
                        )
                    }
                }
            }
        }
    }

    private fun isVariableUsageAllowed(
        variableType: GraphQLTypeRef,
        variableDefault: Value?,
        locationType: GraphQLTypeRef,
        hasLocationDefault: Boolean,
        requiresNonNull: Boolean,
    ): Boolean {
        val effectiveLocationType = if (requiresNonNull && locationType !is GraphQLTypeRef.NonNull) {
            GraphQLTypeRef.NonNull(locationType)
        } else {
            locationType
        }
        if (effectiveLocationType is GraphQLTypeRef.NonNull && variableType !is GraphQLTypeRef.NonNull) {
            val hasNonNullVariableDefault = variableDefault != null && variableDefault !is Value.NullValue
            if (!hasNonNullVariableDefault && !hasLocationDefault) return false
            return areTypesCompatible(variableType, effectiveLocationType.value)
        }
        return areTypesCompatible(variableType, effectiveLocationType)
    }

    private fun areTypesCompatible(variableType: GraphQLTypeRef, locationType: GraphQLTypeRef): Boolean = when {
        locationType is GraphQLTypeRef.NonNull ->
            variableType is GraphQLTypeRef.NonNull && areTypesCompatible(variableType.value, locationType.value)

        variableType is GraphQLTypeRef.NonNull -> areTypesCompatible(variableType.value, locationType)
        locationType is GraphQLTypeRef.ListType ->
            variableType is GraphQLTypeRef.ListType && areTypesCompatible(variableType.element, locationType.element)

        variableType is GraphQLTypeRef.ListType -> false
        else -> (variableType as GraphQLTypeRef.Named).name == (locationType as GraphQLTypeRef.Named).name
    }

    private fun typesOverlap(first: String, second: String): Boolean {
        val firstTypes = schema.possibleObjectTypes(first)
        val secondTypes = schema.possibleObjectTypes(second)
        return firstTypes.any { it in secondTypes }
    }

    private fun error(message: String, location: SourceLocation?): GraphQLError = GraphQLError(
        message = message,
        locations = location?.let { listOf(Location(it.line, it.column)) },
    )
}

private data class VariableUsage(
    val name: String,
    val expectedType: GraphQLTypeRef,
    val hasLocationDefault: Boolean,
    val requiresNonNull: Boolean,
    val location: SourceLocation,
)

private class OperationMetrics(
    private val limits: GraphQLDocumentLimits,
    private val errors: MutableList<GraphQLError>,
) {
    private var selectedFields = 0
    private var aliases = 0
    private var fragmentExpansions = 0
    var exceeded: Boolean = false
        private set

    fun depth(depth: Int): Boolean {
        if (depth <= limits.maxSelectionDepth) return true
        fail("Operation exceeds the selection-depth limit of ${limits.maxSelectionDepth}", null)
        return false
    }

    fun field(hasAlias: Boolean, location: SourceLocation) {
        selectedFields += 1
        if (selectedFields > limits.maxSelectedFields) {
            fail("Operation exceeds the selected-field limit of ${limits.maxSelectedFields}", location)
        }
        if (hasAlias) {
            aliases += 1
            if (aliases > limits.maxAliases) {
                fail("Operation exceeds the alias limit of ${limits.maxAliases}", location)
            }
        }
    }

    fun fragment(location: SourceLocation) {
        fragmentExpansions += 1
        if (fragmentExpansions > limits.maxFragmentExpansions) {
            fail("Operation exceeds the fragment-expansion limit of ${limits.maxFragmentExpansions}", location)
        }
    }

    fun fragmentDepth(depth: Int, location: SourceLocation): Boolean {
        if (depth <= limits.maxFragmentNesting) return true
        fail("Operation exceeds the fragment-nesting limit of ${limits.maxFragmentNesting}", location)
        return false
    }

    private fun fail(message: String, location: SourceLocation?) {
        if (exceeded) return
        exceeded = true
        errors += GraphQLError(
            message = message,
            locations = location?.let { listOf(Location(it.line, it.column)) },
        )
    }
}

private val GraphQLDirectiveLocation.description: String
    get() = name.lowercase().replace('_', ' ')

private fun OperationType.directiveLocation(): GraphQLDirectiveLocation = when (this) {
    OperationType.QUERY -> GraphQLDirectiveLocation.QUERY
    OperationType.MUTATION -> GraphQLDirectiveLocation.MUTATION
    OperationType.SUBSCRIPTION -> GraphQLDirectiveLocation.SUBSCRIPTION
}

private fun GraphQLInputValueDefinition.isRequired(): Boolean =
    type is GraphQLTypeRef.NonNull && defaultValue is OptionalInput.Absent

private fun TypeReference.toSchemaTypeRef(): GraphQLTypeRef = when (this) {
    is TypeReference.Named -> GraphQLTypeRef.Named(name)
    is TypeReference.ListType -> GraphQLTypeRef.ListType(element.toSchemaTypeRef())
    is TypeReference.NonNull -> GraphQLTypeRef.NonNull(value.toSchemaTypeRef())
}

private fun Value.matchesScalar(name: String): Boolean = when (name) {
    "Int" -> this is Value.IntValue && value.toLongOrNull() in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
    "Float" -> when (this) {
        is Value.IntValue -> value.toDoubleOrNull()?.isFinite() == true
        is Value.FloatValue -> value.toDoubleOrNull()?.isFinite() == true
        else -> false
    }
    "String" -> this is Value.StringValue
    "Boolean" -> this is Value.BooleanValue
    "ID" -> this is Value.StringValue || this is Value.IntValue
    else -> true
}

private fun List<Selection>.fragmentSpreads(): List<FragmentSpread> = buildList {
    this@fragmentSpreads.forEach { selection ->
        when (selection) {
            is FieldSelection -> addAll(selection.selections.fragmentSpreads())
            is FragmentSpread -> add(selection)
            is InlineFragment -> addAll(selection.selections.fragmentSpreads())
        }
    }
}
