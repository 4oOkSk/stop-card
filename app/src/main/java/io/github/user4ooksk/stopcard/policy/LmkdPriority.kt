package io.github.user4ooksk.stopcard.policy

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Rewrite only verified process-priority packets; retain all native flags and caller buffers. */
object LmkdPriority {
    fun rewrite(request: ByteBuffer, adjust: (Int, Int, Int) -> Int): ByteBuffer? {
        val size = request.position()
        if (request.order() != ByteOrder.BIG_ENDIAN || size < 16 || size > 4096) return null
        val offsets = when (request.getInt(0)) {
            1 -> if (size in setOf(16, 20, 24)) listOf(4) else return null
            11 -> if ((size - 4) % 20 == 0) (4 until size step 20).toList() else return null
            else -> return null
        }
        var result: ByteBuffer? = null
        for (offset in offsets) {
            val pid = request.getInt(offset)
            val uid = request.getInt(offset + 4)
            val native = request.getInt(offset + 8)
            if (pid <= 0 || uid < 10000 || native !in 900..999) continue
            val value = adjust(pid, uid, native)
            if (value !in 700..899 || value >= native) continue
            if (result == null) {
                result = ByteBuffer.allocate(request.capacity()).order(request.order())
                val input = request.duplicate().apply { limit(size); position(0) }
                result.put(input)
            }
            result.putInt(offset + 8, value)
        }
        return result
    }
}
