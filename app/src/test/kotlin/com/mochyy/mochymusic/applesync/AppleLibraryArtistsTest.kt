// Concord: new — Apple's library-artist list, the shape this app keys the `artist` table by.
//
// The list read is Cider's: `/v1/me/library/artists?sort=name&include=catalog`. The captured row
// (`cider/fixtures/ep_artists.json`) carries `attributes.name` only; `include=catalog` adds
// `relationships.catalog.data[0]` with the catalogue id and picture, and that data array can be
// empty (the library-artist fixture for the same account shows exactly that).
package com.mochyy.mochymusic.applesync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleLibraryArtistsTest {

    @Test
    fun `a row with a catalogue twin answers its id, name and picture`() {
        val page = Json.parseToJsonElement(
            """
            {
              "data": [
                {
                  "id": "r.jV027u",
                  "type": "library-artists",
                  "attributes": { "name": "Nancy Ajram & Marshmello" },
                  "relationships": {
                    "catalog": {
                      "data": [
                        {
                          "id": "1553823070",
                          "type": "artists",
                          "attributes": {
                            "name": "Nancy Ajram & Marshmello",
                            "artwork": { "url": "https://is1-ssl.mzstatic.com/image/thumb/{w}x{h}bb.jpg" }
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

        val row = AppleLibrary.artists(page).single()
        assertEquals("r.jV027u", row.id)
        assertEquals("Nancy Ajram & Marshmello", row.name)
        assertEquals("1553823070", row.catalogId)
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/{w}x{h}bb.jpg", row.artworkUrl)
    }

    @Test
    fun `an empty catalogue relationship still arrives as a row`() {
        val page = Json.parseToJsonElement(
            """
            {
              "data": [
                {
                  "id": "r.XAHP6Je",
                  "type": "library-artists",
                  "attributes": { "name": "Alan Walker & Ava Max" },
                  "relationships": { "catalog": { "data": [] } }
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val row = AppleLibrary.artists(page).single()
        assertEquals("r.XAHP6Je", row.id)
        assertEquals("Alan Walker & Ava Max", row.name)
        assertNull(row.catalogId)
        assertNull(row.artworkUrl)
    }

    @Test
    fun `the single-artist read answers the catalogue twin behind a library artist`() {
        val page = Json.parseToJsonElement(
            """
            {
              "data": [
                {
                  "id": "r.jV027u",
                  "type": "library-artists",
                  "attributes": { "name": "Nancy Ajram & Marshmello" },
                  "relationships": { "catalog": { "data": [ { "id": "1553823070", "type": "artists" } ] } }
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        assertEquals("1553823070", AppleLibrary.catalogArtistId(page))
    }
}
