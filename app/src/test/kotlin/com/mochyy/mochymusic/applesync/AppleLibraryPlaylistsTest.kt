// Concord: new — the playlist list row carries everything a per-playlist read used to be spent on.
//
// Probed on this account 2026-11-24: `/v1/me/library/playlists?limit=100&include=catalog` returns,
// on each row, `relationships.catalog.data[0].id` = `pl.u-...` and
// `attributes.curatorName` = "Faizaan". `include=curator` answers no relationship at all, and
// `fields[library-playlists]=curatorName` answers a resource without it — so the included catalogue
// twin is the only place the list can get the owner, and it is enough.
package com.mochyy.mochymusic.applesync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleLibraryPlaylistsTest {

    @Test
    fun `a list row answers the curator name and the catalogue id`() {
        val page = Json.parseToJsonElement(
            """
            {
              "data": [
                {
                  "id": "p.7PkeeLesW44dM2v",
                  "type": "library-playlists",
                  "attributes": {
                    "name": "Am I God?",
                    "canEdit": true,
                    "canDelete": true,
                    "hasCollaboration": false,
                    "lastModifiedDate": "2026-11-20T10:00:00Z"
                  },
                  "relationships": {
                    "catalog": {
                      "data": [
                        {
                          "id": "pl.u-55D6XYyFXYYrxBR",
                          "type": "playlists",
                          "attributes": { "curatorName": "Faizaan", "curatorSocialHandle": "faizaan" }
                        }
                      ]
                    }
                  }
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val row = AppleLibrary.playlists(page).single()
        assertEquals("Am I God?", row.name)
        assertEquals("Faizaan", row.curator)
        assertEquals("pl.u-55D6XYyFXYYrxBR", row.catalogId)
    }

    @Test
    fun `a playlist Apple has no catalogue twin for still arrives as a row`() {
        val page = Json.parseToJsonElement(
            """
            {
              "data": [
                {
                  "id": "p.uploadOnly",
                  "type": "library-playlists",
                  "attributes": { "name": "Mine", "canEdit": true, "canDelete": true },
                  "relationships": { "catalog": { "data": [] } }
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val row = AppleLibrary.playlists(page).single()
        assertEquals("Mine", row.name)
        assertNull(row.curator)
        assertNull(row.catalogId)
    }
}
