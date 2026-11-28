// Concord: new — the two decisions the artist merge makes, away from the database: what counts as the
// same name, and which row survives.

package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.db.entities.ArtistEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleArtistMergeTest {

    private fun artist(id: String, name: String, picture: String? = null) =
        ArtistEntity(id = id, name = name, thumbnailUrl = picture)

    @Test
    fun `the same name written differently is the same artist`() {
        // the three shapes the library actually holds: a dash variant, a double space inside the
        // credit line, and an accent
        assertEquals(artistNameKey("K‐391"), artistNameKey("K-391"))
        assertEquals(artistNameKey("Talia  Lahoud"), artistNameKey("Talia Lahoud"))
        assertEquals(artistNameKey("Sigur Rós"), artistNameKey("Sigur Ros"))
        assertEquals(artistNameKey("HUNTR/X"), artistNameKey("HUNTRX"))
    }

    @Test
    fun `different names stay different`() {
        assertEquals(false, artistNameKey("Kado") == artistNameKey("Kado 2"))
        assertEquals(false, artistNameKey("Justin Bieber") == artistNameKey("Justin Timberlake"))
    }

    @Test
    fun `the catalogue artist is the row the others fold into`() {
        val survivor = AppleArtistMerge.pickSurvivor(
            listOf(
                artist("UC5DHwv9N4OQ9AWtKr8YSefw", "Ado"),
                artist("r.ESagXR6", "Ado"),
                artist("1492604670", "Ado"),
                artist("LALBGNFQEh", "Ado"),
            ),
        )
        assertEquals("1492604670", survivor?.id)
    }

    @Test
    fun `a library artist beats a generated one`() {
        val survivor = AppleArtistMerge.pickSurvivor(
            listOf(
                artist("LAHEuMUoyR", "Conor Maynard"),
                artist("r.3EwxKa2", "Conor Maynard"),
            ),
        )
        assertEquals("r.3EwxKa2", survivor?.id)
    }

    @Test
    fun `two catalogue artists with one name are left alone`() {
        // "Kado" exists twice in Apple's catalogue, and a merge cannot be undone
        assertNull(
            AppleArtistMerge.pickSurvivor(
                listOf(
                    artist("r.FkvrvNi", "Kado"),
                    artist("1528028116", "Kado"),
                    artist("1675566756", "Kado"),
                ),
            ),
        )
    }

    @Test
    fun `a merged row keeps the picture its survivor lacks`() {
        // the picture decides between rows of the same rank, so the face is not lost
        val survivor = AppleArtistMerge.pickSurvivor(
            listOf(
                artist("r.aaaa", "Someone"),
                artist("r.bbbb", "Someone", picture = "https://example.test/face.jpg"),
            ),
        )
        assertEquals("r.bbbb", survivor?.id)
    }
}
