package com.aipaca.app.agent.memory

import android.util.Log
import com.aipaca.app.agent.mcp.ToolResult
import com.aipaca.app.agent.mcp.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val TAG = "SkillTools"

/**
 * Two local tools for the skills system:
 * - `skill_view` — loads a skill's full body on demand (progressive disclosure level 1)
 * - `skill_manage` — create, patch, or delete skills
 */
object SkillTools {

    // ---- skill_view ---------------------------------------------------------

    const val VIEW_NAME = "skill_view"
    private const val VIEW_DESCRIPTION = "Load the full procedure of a learned skill by name."

    private val VIEW_SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("name") {
                put("type", "string")
                put("description", "Skill name from the index")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("name")) }
    }

    fun viewSpec(): ToolSpec = ToolSpec(
        name = VIEW_NAME,
        description = VIEW_DESCRIPTION,
        inputSchema = VIEW_SCHEMA
    )

    fun view(args: JsonObject, store: SkillStore): ToolResult {
        val name = args["name"]?.jsonPrimitive?.content
            ?: return ToolResult("Missing 'name' parameter", isError = true)
        val skill = store.view(name)
            ?: return ToolResult("Skill '$name' not found. Check the skill index for available names.", isError = true)
        return ToolResult(text = "# ${skill.name}\n${skill.description}\n\n${skill.body}")
    }

    // ---- skill_manage -------------------------------------------------------

    const val MANAGE_NAME = "skill_manage"
    private const val MANAGE_DESCRIPTION = "Create, update, or delete a learned skill/procedure."

    private val MANAGE_SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                putJsonArray("enum") { add(JsonPrimitive("create")); add(JsonPrimitive("patch")); add(JsonPrimitive("delete")) }
                put("description", "Operation to perform")
            }
            putJsonObject("name") {
                put("type", "string")
                put("description", "Skill name (lowercase-hyphenated, max 64 chars)")
            }
            putJsonObject("description") {
                put("type", "string")
                put("description", "Short description for the index (max 60 chars, for create)")
            }
            putJsonObject("body") {
                put("type", "string")
                put("description", "Full skill procedure in markdown (for create/patch)")
            }
            putJsonObject("category") {
                put("type", "string")
                put("description", "Optional category for grouping")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("action")); add(JsonPrimitive("name")) }
    }

    fun manageSpec(): ToolSpec = ToolSpec(
        name = MANAGE_NAME,
        description = MANAGE_DESCRIPTION,
        inputSchema = MANAGE_SCHEMA
    )

    fun manage(args: JsonObject, store: SkillStore): ToolResult {
        val action = args["action"]?.jsonPrimitive?.content
            ?: return ToolResult("Missing 'action' parameter", isError = true)
        val name = args["name"]?.jsonPrimitive?.content
            ?: return ToolResult("Missing 'name' parameter", isError = true)

        return when (action) {
            "create" -> {
                val description = args["description"]?.jsonPrimitive?.content
                    ?: return ToolResult("Missing 'description' for create", isError = true)
                val body = args["body"]?.jsonPrimitive?.content ?: ""
                val category = args["category"]?.jsonPrimitive?.content ?: ""

                // Anti-poisoning check on body
                if (body.isNotBlank() && AntiPoisoning.isPoisoned(body)) {
                    Log.w(TAG, "Rejected poisoned skill body: ${body.take(60)}")
                    return ToolResult("Rejected: do not persist transient errors or negative claims.", isError = true)
                }

                store.save(Skill(name = name, description = description, category = category, body = body))
                ToolResult("Skill '$name' created.")
            }
            "patch" -> {
                val body = args["body"]?.jsonPrimitive?.content
                    ?: return ToolResult("Missing 'body' for patch", isError = true)

                if (AntiPoisoning.isPoisoned(body)) {
                    Log.w(TAG, "Rejected poisoned skill patch: ${body.take(60)}")
                    return ToolResult("Rejected: do not persist transient errors or negative claims.", isError = true)
                }

                if (store.view(name) == null) {
                    return ToolResult("Skill '$name' not found. Use 'create' first.", isError = true)
                }
                store.patch(name, body)
                ToolResult("Skill '$name' updated.")
            }
            "delete" -> {
                store.delete(name)
                ToolResult("Skill '$name' deleted.")
            }
            else -> ToolResult("Unknown action '$action'. Use 'create', 'patch', or 'delete'.", isError = true)
        }
    }
}
