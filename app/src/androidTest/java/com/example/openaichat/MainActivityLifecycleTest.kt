package com.example.openaichat

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityLifecycleTest {
    @Test
    fun callbackMutationPassesWhileResumed() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val chatId = currentChatId(activity)
            val generation = activeGeneration(activity)
            val itemId = addAssistantItem(activity)

            invokeCallback(activity, chatId, generation, itemId, "updated")

            assertEquals("updated", adapterSnapshot(activity).first { it.id == itemId }.text)
        }
        scenario.close()
    }

    @Test
    fun lateCallbackIsRejectedAfterStopWithoutChangingGeneration() {
        var chatId = ""
        var generation = -1L
        var itemId = ""

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            chatId = currentChatId(activity)
            generation = activeGeneration(activity)
            assertTrue(isRequestCurrent(activity, chatId, generation))
            itemId = addAssistantItem(activity)
        }

        scenario.moveToState(Lifecycle.State.CREATED)

        scenario.onActivity { activity ->
            assertEquals(generation, activeGeneration(activity))
            assertFalse(isRequestCurrent(activity, chatId, generation))

            invokeCallback(activity, chatId, generation, itemId, "late")

            assertEquals("initial", adapterSnapshot(activity).first { it.id == itemId }.text)
        }
        scenario.close()
    }

    @Test
    fun zipSourceChatCompletionConsumesOnlyCapturedPersistedZip() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val chatId = currentChatId(activity)
            val pendingZipUri = "content://example/archive.zip"
            val pendingFileUri = "content://example/notes.txt"
            val state = JSONObject()
                .put(
                    "pending_uploads",
                    JSONArray()
                        .put(JSONObject().put("uri", pendingZipUri).put("kind", "zip"))
                        .put(JSONObject().put("uri", pendingFileUri).put("kind", "file"))
                )

            val prefs = privateField(activity, "prefs") as SharedPreferences
            prefs.edit().putString("composer_state_$chatId", state.toString()).commit()

            invokeSaveUploadedFileToComposer(
                activity = activity,
                chatId = chatId,
                fileId = "file-from-zip",
                name = "extracted.txt",
                size = 12L,
                rawUri = pendingZipUri,
                localPath = "/tmp/extracted.txt",
                mime = "text/plain",
                pendingUploadKind = "zip"
            )

            val saved = JSONObject(prefs.getString("composer_state_$chatId", "") ?: "")
            val pending = saved.getJSONArray("pending_uploads")
            assertEquals(1, pending.length())
            assertEquals(pendingFileUri, pending.getJSONObject(0).getString("uri"))
            assertEquals("file", pending.getJSONObject(0).getString("kind"))
        }
        scenario.close()
    }

    @Test
    fun zipPreparationFailureClearsPreparationState() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val brokenZip = java.io.File(activity.cacheDir, "broken.zip").apply {
                writeText("not a zip archive")
            }
            @Suppress("UNCHECKED_CAST")
            val pending = privateField(activity, "pendingUploadUris") as MutableList<Pair<String, String>>
            pending.add(android.net.Uri.fromFile(brokenZip).toString() to "zip")

            invoke(
                activity,
                "preparePendingZipsThenSend",
                Boolean::class.javaPrimitiveType!!,
                false
            )
        }

        for (attempt in 0 until 40) {
            var complete = false
            scenario.onActivity { activity ->
                complete = !(privateField(activity, "preparingZipAttachments") as Boolean)
            }
            if (complete) break
            SystemClock.sleep(25L)
        }

        scenario.onActivity { activity ->
            assertFalse(privateField(activity, "preparingZipAttachments") as Boolean)
            val status = privateField(activity, "status") as android.widget.TextView
            assertTrue(status.text.toString().contains("Не удалось подготовить ZIP"))
        }
        scenario.close()
    }

    @Test
    fun lateCallbackIsRejectedAfterDeleteAndDeletedChatStaysGone() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val chatId = currentChatId(activity)
            val generation = activeGeneration(activity)
            assertTrue(isRequestCurrent(activity, chatId, generation))
            val itemId = addAssistantItem(activity)

            activity.deleteChat(chatId)

            val database = privateField(activity, "chatDb") as ChatDatabase
            assertTrue(database.getChat(chatId) == null)
            assertFalse(isRequestCurrent(activity, chatId, generation))
            assertEquals(-1, adapterIndexOf(activity, itemId))

            invokeCallback(activity, chatId, generation, itemId, "late")

            assertEquals(-1, adapterIndexOf(activity, itemId))
        }
        scenario.close()
    }

    private fun addAssistantItem(activity: MainActivity): String =
        activity.addBubble("Astra", "initial", false)

    private fun invokeCallback(
        activity: MainActivity,
        chatId: String,
        generation: Long,
        itemId: String,
        snapshot: String
    ) {
        activity.postAssistantTextUpdate(chatId, generation, itemId, snapshot)
    }

    private fun activeGeneration(activity: MainActivity): Long =
        (privateField(activity, "activeResponseState") as ActiveResponseStateStore).state.generation

    private fun currentChatId(activity: MainActivity): String =
        invoke(activity, "currentChatId") as String

    private fun isRequestCurrent(activity: MainActivity, chatId: String, generation: Long): Boolean =
        invoke(
            activity,
            "isRequestCurrent",
            String::class.java,
            Long::class.javaPrimitiveType!!,
            chatId,
            generation
        ) as Boolean

    private fun adapterSnapshot(activity: MainActivity): List<ChatBubbleItem> =
        (privateField(activity, "messageAdapter") as ChatMessageAdapter).snapshot()

    private fun adapterIndexOf(activity: MainActivity, itemId: String): Int =
        (privateField(activity, "messageAdapter") as ChatMessageAdapter).indexOfId(itemId)

    private fun invokeSaveUploadedFileToComposer(
        activity: MainActivity,
        chatId: String,
        fileId: String,
        name: String,
        size: Long,
        rawUri: String,
        localPath: String,
        mime: String,
        pendingUploadKind: String
    ) {
        invokeMethod(
            activity,
            "saveUploadedFileToComposer",
            arrayOf(
                String::class.java,
                String::class.java,
                String::class.java,
                Long::class.javaPrimitiveType!!,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java
            ),
            arrayOf(chatId, fileId, name, size, rawUri, localPath, mime, pendingUploadKind)
        )
    }

    private fun privateField(activity: MainActivity, name: String): Any {
        val field = MainActivity::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(activity)
    }

    private fun invoke(activity: MainActivity, name: String, vararg args: Any?): Any? =
        invokeMethod(activity, name, args.map { it?.javaClass ?: Any::class.java }.toTypedArray(), args)

    private fun invoke(
        activity: MainActivity,
        name: String,
        argType1: Class<*>,
        arg1: Any?
    ): Any? = invokeMethod(activity, name, arrayOf(argType1), arrayOf(arg1))

    private fun invoke(
        activity: MainActivity,
        name: String,
        argType1: Class<*>,
        argType2: Class<*>,
        arg1: Any?,
        arg2: Any?
    ): Any? = invokeMethod(activity, name, arrayOf(argType1, argType2), arrayOf(arg1, arg2))

    private fun invoke(
        activity: MainActivity,
        name: String,
        argType1: Class<*>,
        argType2: Class<*>,
        argType3: Class<*>,
        arg1: Any?,
        arg2: Any?,
        arg3: Any?
    ): Any? = invokeMethod(activity, name, arrayOf(argType1, argType2, argType3), arrayOf(arg1, arg2, arg3))

    private fun invoke(
        activity: MainActivity,
        name: String,
        argType1: Class<*>,
        argType2: Class<*>,
        argType3: Class<*>,
        argType4: Class<*>,
        arg1: Any?,
        arg2: Any?,
        arg3: Any?,
        arg4: Any?
    ): Any? = invokeMethod(activity, name, arrayOf(argType1, argType2, argType3, argType4), arrayOf(arg1, arg2, arg3, arg4))

    private fun invokeMethod(
        activity: MainActivity,
        name: String,
        parameterTypes: Array<Class<*>>,
        args: Array<out Any?>
    ): Any? {
        val method = MainActivity::class.java.getDeclaredMethod(name, *parameterTypes)
        method.isAccessible = true
        return method.invoke(activity, *args)
    }
}
