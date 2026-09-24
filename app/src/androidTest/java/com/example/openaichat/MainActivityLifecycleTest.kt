package com.example.openaichat

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            val itemId = invoke(activity, "addBubble", String::class.java, String::class.java, Boolean::class.javaPrimitiveType!!,
                "Astra", "initial", false) as String

            invoke(
                activity,
                "postAssistantTextUpdate",
                String::class.java,
                Long::class.javaPrimitiveType!!,
                String::class.java,
                String::class.java,
                chatId,
                generation,
                itemId,
                "updated"
            )

            assertEquals("updated", adapterSnapshot(activity).first { it.id == itemId }.text)
        }
        scenario.close()
    }

    @Test
    fun lateCallbackIsRejectedAfterStopWithoutChangingGeneration() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val chatId = currentChatId(activity)
            val generation = activeGeneration(activity)
            assertTrue(isRequestCurrent(activity, chatId, generation))
            val itemId = invoke(activity, "addBubble", String::class.java, String::class.java, Boolean::class.javaPrimitiveType!!,
                "Astra", "initial", false) as String

            scenario.moveToState(Lifecycle.State.CREATED)

            assertEquals(generation, activeGeneration(activity))
            assertFalse(isRequestCurrent(activity, chatId, generation))

            invoke(
                activity,
                "postAssistantTextUpdate",
                String::class.java,
                Long::class.javaPrimitiveType!!,
                String::class.java,
                String::class.java,
                chatId,
                generation,
                itemId,
                "late"
            )

            assertEquals("initial", adapterSnapshot(activity).first { it.id == itemId }.text)
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
            val itemId = invoke(activity, "addBubble", String::class.java, String::class.java, Boolean::class.javaPrimitiveType!!,
                "Astra", "initial", false) as String

            invoke(activity, "deleteChat", String::class.java, chatId)

            val database = privateField(activity, "chatDb") as ChatDatabase
            assertTrue(database.getChat(chatId) == null)
            assertFalse(isRequestCurrent(activity, chatId, generation))
            assertEquals(-1, adapterIndexOf(activity, itemId))

            invoke(
                activity,
                "postAssistantTextUpdate",
                String::class.java,
                Long::class.javaPrimitiveType!!,
                String::class.java,
                String::class.java,
                chatId,
                generation,
                itemId,
                "late"
            )

            assertEquals(-1, adapterIndexOf(activity, itemId))
        }
        scenario.close()
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
