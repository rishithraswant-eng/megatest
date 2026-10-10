package org.seedhecode.incidentassistant.data

/**
 * The only place app logic refers to taxonomy field names. Review, submit and invalidation resolve
 * fields through these roles; fix a name here and every caller follows. Matching ignores case so the
 * Python fixtures ("Description", "Severity") and the taxonomy ("description") both resolve.
 */
enum class FieldRole(val fieldNames: List<String>) {
    /** Becomes incidents.description_en. Verified: common_fields.description. */
    DESCRIPTION(listOf("description")),

    /** NOT a form field in incident_taxonomy.json (priority is a system field); only fixtures that seed one use it. */
    PRIORITY(listOf("priority")),

    /** NOT in incident_taxonomy.json at all; kept so the ported dependency rule applies to fixtures that define it. */
    SEVERITY(listOf("severity")),

    /**
     * space_id is matched against zones.name. The taxonomy asks it as "Which room or area exactly, for example
     * Conference Room 2 or the pantry?", and the demo zones (assets/demo_zones.json) use those room names.
     * building/floor are not part of the match: zone names are assumed unique within the org.
     */
    LOCATION(listOf("space_id"));

    fun matches(fieldName: String): Boolean = fieldNames.any { it.equals(fieldName, ignoreCase = true) }

    companion object {
        fun of(fieldName: String): FieldRole? = entries.firstOrNull { it.matches(fieldName) }

        /** Correcting the key role invalidates the dependent roles (ported from review_manager.DEPENDENCY_MAP). */
        val DEPENDENCIES: Map<FieldRole, List<FieldRole>> = mapOf(DESCRIPTION to listOf(PRIORITY), SEVERITY to listOf(PRIORITY))

        /** First non-blank value among fields playing [role]. */
        fun valueOf(role: FieldRole, values: Map<String, String?>): String? = role.fieldNames.firstNotNullOfOrNull { name ->
            values.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
        }

        /**
         * Zone name-match signal; null unless every LOCATION part has a value. A leading "the" is dropped for
         * matching only ("the pantry" -> "pantry"); the stored field value is never altered.
         */
        fun locationSignal(values: Map<String, String?>): String? = LOCATION.fieldNames.map { name ->
            values.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()
                ?.replace(LEADING_ARTICLE, "")?.takeIf { it.isNotEmpty() } ?: return null
        }.joinToString(" ")

        private val LEADING_ARTICLE = Regex("^the\\s+", RegexOption.IGNORE_CASE)
    }
}
