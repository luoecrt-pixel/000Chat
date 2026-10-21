package com.example.openaichat

import org.junit.Assert.*
import org.junit.Test

class ContextToolSchemasTest {
    @Test fun builtInsKeepActualWireOptionsAndStableOrder() {
        val tools = ContextToolSchemas.openAi(true, true, "vs-captured", true, "4g",
            listOf("file-a", "", "file-a", "file-b", "  "), true)
        assertEquals(listOf("web_search", "file_search", "code_interpreter", "image_generation"),
            (0 until tools.length()).map { tools.getJSONObject(it).getString("type") })
        val search = tools.getJSONObject(1)
        assertEquals(20, search.getInt("max_num_results"))
        assertEquals("vs-captured", search.getJSONArray("vector_store_ids").getString(0))
        assertEquals(1, search.getJSONArray("vector_store_ids").length())
        val container = tools.getJSONObject(2).getJSONObject("container")
        assertEquals("auto", container.getString("type"))
        assertEquals("4g", container.getString("memory_limit"))
        assertEquals(listOf("file-a", "file-b"), (0 until container.getJSONArray("file_ids").length())
            .map { container.getJSONArray("file_ids").getString(it) })
        assertEquals(1, tools.getJSONObject(0).length())
        assertEquals(1, tools.getJSONObject(3).length())
    }

    @Test fun fileSearchRequiresEnabledToolAndNonblankVectorStore() {
        assertEquals(0, ContextToolSchemas.openAi(false, true, "  ", false, "1g", emptyList(), false).length())
        assertEquals(0, ContextToolSchemas.openAi(false, false, "vs-present", false, "1g", emptyList(), false).length())
    }

    @Test fun automaticEmptyContainerOmitsFileIdsAndDisabledContainerIsNotDeclared() {
        val tools = ContextToolSchemas.openAi(false, false, "", true, "1g", listOf("", " "), false)
        assertEquals(1, tools.length())
        assertFalse(tools.getJSONObject(0).getJSONObject("container").has("file_ids"))
        assertEquals(0, ContextToolSchemas.openAi(false, false, "", false, "1g", listOf("file-ignored"), false).length())
        // No credential, message or account state belongs in this tool schema surface.
        assertEquals(setOf("type", "container"), tools.getJSONObject(0).keys().asSequence().toSet())
    }

    @Test fun independentSnapshotsDoNotShareMutableJson() {
        val first = ContextToolSchemas.openAi(false, true, "vs-original", false, "1g", emptyList(), false)
        first.getJSONObject(0).getJSONArray("vector_store_ids").put(0, "changed")
        val second = ContextToolSchemas.openAi(false, true, "vs-original", false, "1g", emptyList(), false)
        assertEquals("vs-original", second.getJSONObject(0).getJSONArray("vector_store_ids").getString(0))
    }
}
