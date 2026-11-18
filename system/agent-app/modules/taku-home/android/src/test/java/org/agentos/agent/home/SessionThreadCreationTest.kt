package org.agentos.agent.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.agentos.agent.home.assistant.SessionThreadCreation
import org.junit.Assert.*
import org.junit.Test

class SessionThreadCreationTest {
  @Test fun voiceAndHandoffShareThreadInEitherOrder() = runBlocking {
    for (voiceFirst in listOf(true, false)) {
      val creation = SessionThreadCreation()
      val release = CompletableDeferred<Unit>()
      var current: String? = null
      var requests = 0
      var bindings = 0
      suspend fun resolve() = creation.resolve(0, { current }, {
        requests++
        release.await()
        "thread-$requests"
      }, { current = it; bindings++ })
      val first = async(start = CoroutineStart.UNDISPATCHED) { resolve() }
      val second = async(start = CoroutineStart.UNDISPATCHED) { resolve() }
      release.complete(Unit)
      val voice = if (voiceFirst) first.await() else second.await()
      val chat = if (voiceFirst) second.await() else first.await()
      assertEquals(voice, chat)
      assertEquals(chat, current)
      assertEquals(1, requests)
      assertEquals(1, bindings)
    }
  }

  @Test fun resetRejectsInFlightAndQueuedResults() = runBlocking {
    val creation = SessionThreadCreation()
    val release = CompletableDeferred<Unit>()
    var bound: String? = null
    var requests = 0
    suspend fun resolve() = creation.resolve(0, { bound }, {
      requests++
      release.await()
      "old"
    }, { bound = it })
    val first = launch(start = CoroutineStart.UNDISPATCHED) { resolve() }
    val second = launch(start = CoroutineStart.UNDISPATCHED) { resolve() }
    creation.invalidate()
    release.complete(Unit)
    first.join(); second.join()
    assertTrue(first.isCancelled)
    assertTrue(second.isCancelled)
    assertNull(bound)
    assertEquals(1, requests)
    assertEquals("new", creation.resolve(creation.generation, { bound }, { "new" }, { bound = it }))
    assertEquals("new", bound)
  }

  @Test fun failedCreationCanBeRetried() = runBlocking {
    val creation = SessionThreadCreation()
    var bound: String? = null
    try {
      creation.resolve(0, { bound }, { error("gateway unavailable") }, { bound = it })
      fail("Expected gateway failure")
    } catch (_: IllegalStateException) { }
    assertNull(bound)
    assertEquals("retry", creation.resolve(0, { bound }, { "retry" }, { bound = it }))
  }

  @Test fun cancelledCreatorDoesNotBindOrBlockNextCaller() = runBlocking {
    val creation = SessionThreadCreation()
    val release = CompletableDeferred<Unit>()
    var bound: String? = null
    val first = launch(start = CoroutineStart.UNDISPATCHED) {
      creation.resolve(0, { bound }, { release.await(); "cancelled" }, { bound = it })
    }
    val second = async(start = CoroutineStart.UNDISPATCHED) {
      creation.resolve(0, { bound }, { "voice" }, { bound = it })
    }
    first.cancelAndJoin()
    assertEquals("voice", second.await())
    assertEquals("voice", bound)
  }
}
