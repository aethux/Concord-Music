// Concord: new — the paging fix's one moving part: what a second page actually asks for.
//
// The live shapes here were probed on this account 2026-11-24: the first page of the library read
// answers 100 songs and its `next` is `/v1/me/library/songs?offset=100`; that bare offset answers
// 25 rows, and the same URL with `&limit=100` answers 100 and steps to `offset=200`.
package com.mochyy.mochymusic.applesync

import org.junit.Assert.assertEquals
import org.junit.Test

class AppleClientPagingTest {

    private val songsNext = "/v1/me/library/songs?offset=100"

    @Test
    fun `a bare offset next keeps both the include and the limit`() {
        assertEquals(
            "/v1/me/library/songs?offset=100&include=albums&limit=100",
            withKeptParams(songsNext, listOf("include=albums", "limit=100")),
        )
    }

    @Test
    fun `a parameter the next link already carries is not repeated`() {
        assertEquals(
            "/v1/me/library/songs?offset=100&limit=100",
            withKeptParams("/v1/me/library/songs?offset=100&limit=100", listOf("limit=100")),
        )
    }

    @Test
    fun `nothing kept leaves the next link exactly as Apple sent it`() {
        assertEquals(songsNext, withKeptParams(songsNext, emptyList()))
    }

    @Test
    fun `a next link with no query still gets one`() {
        assertEquals(
            "/v1/me/library/songs?limit=100",
            withKeptParams("/v1/me/library/songs", listOf("limit=100")),
        )
    }

    @Test
    fun `blank fragments from a trailing ampersand are ignored`() {
        assertEquals(
            "/x?offset=1&limit=100",
            withKeptParams("/x?offset=1", listOf("limit=100", "")),
        )
    }
}
