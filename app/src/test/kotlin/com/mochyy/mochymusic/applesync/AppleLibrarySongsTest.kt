// Concord: new — a library song's split artists, the way Apple's own page groups them.
//
// Probed on the account 2026-11-26 with `include=albums,artists,catalog&include[songs]=artists`:
// the library row's own `relationships.artists` is ONE library artist (an `r.…` bucket, sometimes a
// combined "A & B" entity), while the catalogue twin's `relationships.artists` is the split list of
// numeric catalogue artists. 257 of 300 sampled songs carried a twin; 85 of those named more than
// one artist. The split list is the one that matches Apple's and Cider's artists.
package com.mochyy.mochymusic.applesync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AppleLibrarySongsTest {

    @Test
    fun `the catalogue twin's artists are the split list`() {
        val page = Json.parseToJsonElement(
            """
            {
              "data": [
                {
                  "id": "i.mmpeK1kHL44AxYP",
                  "type": "library-songs",
                  "attributes": {
                    "artistName": "Alan Walker & Philter",
                    "name": "Adventure Time",
                    "playParams": { "catalogId": "1625863071", "id": "i.mmpeK1kHL44AxYP", "kind": "song" }
                  },
                  "relationships": {
                    "albums": { "data": [] },
                    "artists": {
                      "data": [
                        { "id": "r.0FfA5zg", "type": "library-artists", "attributes": { "name": "Alan Walker" } }
                      ]
                    },
                    "catalog": {
                      "data": [
                        {
                          "id": "1625863071",
                          "type": "songs",
                          "attributes": { "artistName": "Alan Walker & Philter" },
                          "relationships": {
                            "artists": {
                              "data": [
                                { "id": "1062085272", "type": "artists", "attributes": { "name": "Alan Walker" } },
                                { "id": "276285556", "type": "artists", "attributes": { "name": "Philter" } }
                              ]
                            }
                          }
                        }
                      ]
                    }
                  }
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val row = AppleLibrary.songs(page).single()
        assertEquals("Alan Walker & Philter", row.artist)
        assertEquals(
            listOf(RemoteArtist("1062085272", "Alan Walker"), RemoteArtist("276285556", "Philter")),
            row.artists,
        )
    }

    @Test
    fun `no catalogue twin keeps the library row's own artist`() {
        val page = Json.parseToJsonElement(
            """
            {
              "data": [
                {
                  "id": "i.one",
                  "type": "library-songs",
                  "attributes": { "artistName": "Someone & Other", "name": "A song" },
                  "relationships": {
                    "artists": {
                      "data": [
                        { "id": "r.abc123", "type": "library-artists", "attributes": { "name": "Someone & Other" } }
                      ]
                    }
                  }
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val row = AppleLibrary.songs(page).single()
        assertEquals(listOf(RemoteArtist("r.abc123", "Someone & Other")), row.artists)
    }

    @Test
    fun `a row with no artists relationship falls back to the combined line at import`() {
        val page = Json.parseToJsonElement(
            """
            {
              "data": [
                {
                  "id": "i.two",
                  "type": "library-songs",
                  "attributes": { "artistName": "Someone & Other", "name": "A song" }
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val row = AppleLibrary.songs(page).single()
        assertEquals("Someone & Other", row.artist)
        assertEquals(emptyList<RemoteArtist>(), row.artists)
    }
}
