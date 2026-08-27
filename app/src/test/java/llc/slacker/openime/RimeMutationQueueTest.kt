package llc.slacker.openime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RimeMutationQueueTest {

    @Test
    fun submitReturnsWhileEarlierNativeWorkIsStillBlocked() {
        val queue = RimeMutationQueue("rime-mutation-test")
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondDone = CountDownLatch(1)
        try {
            assertTrue(
                queue.submit {
                    firstStarted.countDown()
                    releaseFirst.await()
                },
            )
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS))

            // This models a space/candidate commit arriving while an earlier
            // native candidate operation still owns the Rime lock. submit()
            // itself must return instead of waiting for that operation.
            assertTrue(queue.submit { secondDone.countDown() })
            assertEquals(1L, secondDone.count)

            releaseFirst.countDown()
            assertTrue(secondDone.await(1, TimeUnit.SECONDS))
        } finally {
            releaseFirst.countDown()
            queue.close()
        }
    }

    @Test
    fun mutationsStaySerial() {
        val queue = RimeMutationQueue("rime-serial-test")
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondDone = CountDownLatch(1)
        val order = mutableListOf<Int>()
        try {
            queue.submit {
                synchronized(order) { order += 1 }
                firstStarted.countDown()
                releaseFirst.await()
                synchronized(order) { order += 2 }
            }
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS))
            queue.submit {
                synchronized(order) { order += 3 }
                secondDone.countDown()
            }

            releaseFirst.countDown()
            assertTrue(secondDone.await(1, TimeUnit.SECONDS))
            assertEquals(listOf(1, 2, 3), synchronized(order) { order.toList() })
        } finally {
            releaseFirst.countDown()
            queue.close()
        }
    }

    @Test
    fun closedQueueRejectsNewMutation() {
        val queue = RimeMutationQueue("rime-closed-test")
        queue.close()

        assertFalse(queue.submit { error("must not run") })
    }
}
