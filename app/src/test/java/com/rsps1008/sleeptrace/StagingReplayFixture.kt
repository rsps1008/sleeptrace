package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.motion.MINUTE_MS
import com.rsps1008.sleeptrace.motion.MotionMinute
import com.rsps1008.sleeptrace.motion.Placement
import java.security.MessageDigest

internal data class StagingReplayFixture(
    val bytes: ByteArray,
    val rows: List<MotionMinute>,
    val sha256: String
)

internal object StagingReplayFixtureReader {
    fun read(base: Long): StagingReplayFixture {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/staging/real_night_style.csv"))
            .use { it.readBytes() }
        return StagingReplayFixture(bytes, parse(bytes, base), sha256(bytes))
    }

    /** Parse exactly the bytes supplied by the caller; hashing never sees normalized text. */
    fun parse(bytes: ByteArray, base: Long): List<MotionMinute> = String(bytes, Charsets.UTF_8)
        .lineSequence()
        .drop(1)
        .filter { it.isNotBlank() }
        .map { line ->
            val values = line.split(',')
            val covered = (values[1].toDouble() * 1000).toLong()
            val rms = values[3].toDouble()
            MotionMinute(
                base + values[0].toLong() * MINUTE_MS,
                covered,
                (values[2].toDouble() * 1000).toLong(),
                rms * rms * covered,
                60,
                Placement.valueOf(values[4]),
                featureVersion = 4
            )
        }
        .toList()

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
