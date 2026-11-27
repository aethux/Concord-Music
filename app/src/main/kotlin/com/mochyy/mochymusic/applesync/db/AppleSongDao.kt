// Concord: copied from Mocha applesync/db/AppleSongDao.kt (apple-sync@c90b8251). Package renamed only.
//
// The Apple-only reads, kept out of upstream's DatabaseDao so the fork's seam budget there stays
// for real seams. Same database, its own dao: Room reads it like any other.

package com.mochyy.mochymusic.applesync.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.mochyy.mochymusic.db.entities.AlbumEntity

@Dao
interface AppleSongDao {
    // the Library's Genres tab groups by this column; the catalogue's genre name fills it
    @Query("UPDATE song SET genre = :genre WHERE id = :songId AND (genre IS NULL OR genre != :genre)")
    suspend fun setSongGenre(songId: String, genre: String)

    // rows with no genre, or with a bare Apple genre id ("13") the library endpoint answered
    @Query("SELECT id FROM song WHERE (genre IS NULL OR genre = '' OR genre NOT GLOB '*[A-Za-z]*') AND id LIKE 'AM_%'")
    suspend fun appleSongIdsWithoutGenre(): List<String>

    // backfill a cover without touching anything else on the row
    @Transaction
    @Query("UPDATE song SET thumbnailUrl = :url WHERE id = :songId AND (thumbnailUrl IS NULL OR thumbnailUrl = '')")
    suspend fun setSongThumbnailIfMissing(songId: String, url: String)

    // a cover answered by Apple's expiring signed blobstore URL is replaced by the fresh template
    @Query("UPDATE song SET thumbnailUrl = :url WHERE id = :songId AND (thumbnailUrl LIKE '%blobstore%' OR thumbnailUrl LIKE '%X-Amz%')")
    suspend fun setSongThumbnailWhenExpired(songId: String, url: String)

    /**
     * The rows whose artwork came from a signed Apple URL — an upload's cover is on the blobstore and
     * its signature lives a day, so each read has to hand over a fresh one. Read up front, like the
     * other sets in this dao, so a refresh touches only the rows that can go stale.
     */
    @Query("SELECT id FROM song WHERE id LIKE 'AM_%' AND (thumbnailUrl LIKE '%blobstore%' OR thumbnailUrl LIKE '%X-Amz%')")
    suspend fun appleSongsWithSignedArtwork(): List<String>

    // the ids this fork imports, so a repeat import skips what it already added without one query per song
    @Query("SELECT id FROM song WHERE id GLOB 'AM_*'")
    suspend fun appleSongIds(): List<String>

    // the album import reads the whole set in one query instead of one per album
    @Query("SELECT * FROM album WHERE id LIKE 'AM_ALB_%'")
    suspend fun appleAlbums(): List<AlbumEntity>

    /**
     * Concord: an upload album's cover is a signed blobstore URL that lives a day, so each read hands
     * over the next one. The WHERE is the guarantee: a picture the person chose, or a permanent
     * `mzstatic` template, never matches and is never touched.
     */
    @Query("UPDATE album SET thumbnailUrl = :url WHERE id = :albumId AND (thumbnailUrl LIKE '%blobstore%' OR thumbnailUrl LIKE '%X-Amz%')")
    suspend fun renewSignedAlbumCover(albumId: String, url: String)

    /**
     * Concord: the library payload hands an uploaded item the uploader's own signed artwork. It
     * replaces a stored catalogue template or an older signature — a picture chosen by hand carries
     * neither host, so it is never touched.
     */
    @Query("UPDATE song SET thumbnailUrl = :url WHERE id = :songId AND (thumbnailUrl LIKE '%blobstore%' OR thumbnailUrl LIKE '%X-Amz%' OR thumbnailUrl LIKE '%mzstatic%')")
    suspend fun setSongThumbnailFromUpload(songId: String, url: String)

    @Query("SELECT id, albumId FROM song WHERE id LIKE 'AM_%'")
    suspend fun appleAlbumLinks(): List<AppleAlbumLink>

    @Query("SELECT id FROM song WHERE id LIKE 'AM_%' AND thumbnailUrl IS NULL")
    suspend fun appleSongsWithoutArtwork(): List<String>

    @Query("SELECT id FROM song WHERE id LIKE 'AM_%' AND inLibrary IS NULL")
    suspend fun appleSongsOutsideTheLibrary(): List<String>
}
