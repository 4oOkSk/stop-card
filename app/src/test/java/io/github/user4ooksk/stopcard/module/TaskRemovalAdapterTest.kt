package io.github.user4ooksk.stopcard.module

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class TaskRemovalAdapterTest {
    private val loader = javaClass.classLoader!!
    private val callbacks = Proxy.newProxyInstance(loader, arrayOf(FrameworkCallbacks::class.java)) { _, _, _ ->
        error("Reading task identity must not invoke policy callbacks")
    } as FrameworkCallbacks
    private val adapter = FrameworkAdapter(loader, "boot", callbacks)

    // Android organizer roots (SplitRoot/Bubbles) have neither a realActivity nor a base intent.
    class OrganizerTask {
        @JvmField val mUserId = 0
        @JvmField val mTaskId = 42
        @JvmField val realActivity: Any? = null
        fun getBaseIntent(): Any? = null
        fun forAllActivities(action: Any, topToBottom: Boolean): Unit = error("Task is being removed")
    }

    class OtherUserTask { @JvmField val mUserId = 10 }

    class EmptyContainerInRecents {
        @JvmField val mUserId = 0
        @JvmField val mTaskId = 77
        @JvmField val realActivity: Any? = null
        fun getBaseIntent(): Any? = null
        fun forAllActivities(action: java.util.function.Consumer<Any>, topToBottom: Boolean) = Unit
    }

    @Test fun ownerlessOrganizerRemovalDoesNotDisableHooks() {
        assertNull(adapter.removalFact(OrganizerTask()))
        assertNull(adapter.removalFact(OrganizerTask()))
    }

    @Test fun emptyContainerAndSecondaryUserTasksAreSkippedInSnapshots() {
        assertNull(adapter.taskFact(EmptyContainerInRecents(), null))
        assertNull(adapter.taskFact(OtherUserTask(), null))
    }

    @Test fun secondaryUserRemovalDoesNotInspectAppIdentity() {
        assertNull(adapter.removalFact(OtherUserTask()))
    }

    @Test fun unsupportedFrameworkFieldsStillFailExplicitly() {
        val error = assertThrows(IllegalStateException::class.java) { adapter.removalFact(Any()) }
        assertTrue(error.message.orEmpty().contains("mUserId"))
    }
}
