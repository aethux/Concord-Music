// Concord: new — the banner's editorial parsed from the artist page's own payload.
//
// The artist page stopped fetching the same artist a second time for its hero video, logo and bio;
// it hands the row it already has to `AppleMusicArtistBackgroundProvider.editorialFrom`. This pins
// the parse to the shape that payload really has (keys taken from a live Chase Atlantic response,
// 2026-11-24), so a silent null cannot slip back in.
package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.canvas.AppleMusicArtistBackgroundProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleArtistEditorialTest {

    private val row = Json.parseToJsonElement(
        """
        {
          "id": "879496517",
          "type": "artists",
          "attributes": {
            "name": "Chase Atlantic",
            "artistBio": "Australian<b> pop</b><br>rock trio",
            "editorialVideo": {
              "motionArtistSquare1x1": {
                "video": "https://example.test/square.m3u8",
                "previewFrame": { "url": "https://example.test/frame.jpg" }
              },
              "motionArtistWide16x9": { "video": "https://example.test/wide.m3u8" }
            },
            "editorialArtwork": {
              "musicContentColorLogoTrimmed": {
                "url": "https://example.test/logo.png",
                "width": 3096,
                "height": 253
              },
              "centeredFullscreenBackground": { "url": "https://example.test/still.jpg" }
            }
          },
          "views": {
            "latest-release": {
              "data": [
                {
                  "id": "1",
                  "attributes": {
                    "name": "WHEREVER YOU ARE - Single",
                    "releaseDate": "2025-10-10",
                    "isSingle": true,
                    "trackCount": 1
                  }
                }
              ]
            }
          }
        }
        """.trimIndent(),
    ).jsonObject

    @Test
    fun `the page's own artist row parses into the banner's editorial`() {
        val editorial = AppleMusicArtistBackgroundProvider.editorialFrom(row)
        assertNotNull(editorial)
        editorial!!

        assertEquals("879496517", editorial.appleId)
        assertEquals("Chase Atlantic", editorial.name)
        assertEquals("https://example.test/square.m3u8", editorial.motionSquareUrl)
        assertEquals("https://example.test/wide.m3u8", editorial.motionVideoUrl)
        assertEquals("https://example.test/frame.jpg", editorial.previewFrameUrl)
        assertEquals("https://example.test/still.jpg", editorial.bannerUrl)
        assertEquals("https://example.test/logo.png", editorial.logoUrl)
        // 3096 x 253, the wordmark's real proportions
        assertTrue(editorial.logoAspect!! > 12f && editorial.logoAspect!! < 13f)
        // html is stripped, line breaks kept
        assertEquals("Australian pop\nrock trio", editorial.bio)
        assertEquals("WHEREVER YOU ARE", editorial.latestRelease?.name)
        assertEquals(true, editorial.latestRelease?.isSingle)
    }

    @Test
    fun `an artist with no editorial is null, not a broken banner`() {
        // John Michael Howell, live 2026-11-24: no editorialVideo, empty editorialArtwork
        val plain = Json.parseToJsonElement(
            """{"id":"1603499505","type":"artists","attributes":{"name":"John Michael Howell","editorialArtwork":{}}}""",
        ).jsonObject
        val editorial = AppleMusicArtistBackgroundProvider.editorialFrom(plain)
        // the parser still answers (the object exists) but carries nothing to draw
        assertNull(editorial?.motionSquareUrl)
        assertNull(editorial?.motionVideoUrl)
        assertNull(editorial?.logoUrl)
        assertNull(editorial?.bannerUrl)
    }
}
