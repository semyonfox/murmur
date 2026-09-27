package dev.local.murmur

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class CleanupPrompt(val id: String, val name: String, val instructions: String)

internal class CleanupPrompts(context: Context) {
    private val preferences = context.getSharedPreferences("murmur", Context.MODE_PRIVATE)
    val all: List<CleanupPrompt> get() = runCatching {
        val array = JSONArray(preferences.getString("cleanup_prompts", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            CleanupPrompt(item.getString("id"), item.getString("name"), item.getString("instructions"))
        }
    }.getOrDefault(emptyList())
    val selectedId: String? get() = preferences.getString("selected_cleanup_prompt", null)
    val selected: CleanupPrompt? get() = all.firstOrNull { it.id == selectedId }

    fun select(id: String?) {
        require(id == null || all.any { it.id == id })
        preferences.edit().apply {
            if (id == null) remove("selected_cleanup_prompt") else putString("selected_cleanup_prompt", id)
        }.apply()
    }

    fun save(id: String?, name: String, instructions: String) {
        val title = name.trim()
        val body = instructions.trim()
        require(title.length in 1..80 && body.length in 1..4000) { "Use a name and instructions under 4,000 characters." }
        val prompt = CleanupPrompt(id ?: UUID.randomUUID().toString(), title, body)
        val next = all.filterNot { it.id == prompt.id } + prompt
        require(next.size <= 20) { "Maximum 20 cleanup prompts." }
        write(next, prompt.id)
    }

    fun delete(id: String) {
        write(all.filterNot { it.id == id }, selectedId?.takeUnless { it == id })
    }

    private fun write(prompts: List<CleanupPrompt>, selected: String?) {
        val array = JSONArray(prompts.map { prompt -> JSONObject()
            .put("id", prompt.id).put("name", prompt.name).put("instructions", prompt.instructions) })
        preferences.edit().putString("cleanup_prompts", array.toString()).apply {
            if (selected == null) remove("selected_cleanup_prompt") else putString("selected_cleanup_prompt", selected)
        }.apply()
    }
}
