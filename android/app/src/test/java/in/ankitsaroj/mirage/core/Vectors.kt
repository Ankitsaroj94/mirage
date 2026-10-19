package `in`.ankitsaroj.mirage.core

import org.json.JSONObject

/** spec/vectors.json — generated from the Swift implementation; both sides must reproduce it. */
object Vectors {
    val root: JSONObject by lazy {
        val stream = checkNotNull(javaClass.classLoader!!.getResourceAsStream("vectors.json")) { "vectors.json not on the test classpath" }
        JSONObject(stream.readBytes().toString(Charsets.UTF_8))
    }

    fun hex(s: String): ByteArray = Crypto.hex(s)
    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /** u64 values are strings in the vectors (they exceed a double's precision); parse as unsigned bits. */
    fun u64(s: String): Long = s.toULong().toLong()
}
