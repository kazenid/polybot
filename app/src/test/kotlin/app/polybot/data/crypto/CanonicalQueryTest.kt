package app.polybot.data.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanonicalQueryTest {
    @Test
    fun `shared vectors from official SDK`() {
        val vectors = mapOf(
            "z=1&a=hello world&m=a+b" to "a=hello%20world&m=a%20b&z=1",
            "z=1&a=hello%20world&m=a%2Bb" to "a=hello%20world&m=a%2Bb&z=1",
            "b=&a=1" to "a=1&b=",
            "a=1&a=2&b=0" to "a=1&a=2&b=0",
            "path=foo/bar&name=a_b.c~d-e" to "name=a_b.c~d-e&path=foo%2Fbar",
            "msg=%E2%9C%93&plain=ok" to "msg=%E2%9C%93&plain=ok",
        )
        for ((input, expected) in vectors) {
            assertEquals(expected, CanonicalQuery.canonicalize(input), "input: $input")
        }
    }

    @Test
    fun `empty or null query is empty`() {
        assertEquals("", CanonicalQuery.canonicalize(null))
        assertEquals("", CanonicalQuery.canonicalize(""))
    }

    @Test
    fun `component encoding keeps unreserved and encodes the rest`() {
        assertEquals("api-keys", CanonicalQuery.encode("api-keys"))
        assertEquals("hello%20world", CanonicalQuery.encode("hello world"))
        assertEquals("a%2Bb", CanonicalQuery.encode("a+b"))
        assertEquals(
            "private%3Aauth%3Aapi-keys%3Aaccount%3Aproto",
            CanonicalQuery.encode("private:auth:api-keys:account:proto"),
        )
    }

    @Test
    fun `supplementary characters round trip as utf-8`() {
        assertEquals("e=%F0%9F%98%80", CanonicalQuery.canonicalize("e=😀"))
    }
}
