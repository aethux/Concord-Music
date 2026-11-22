// Concord: copied from Mocha applesync/db/AppleSyncDatabase.kt (apple-sync@c90b8251), with one
// deliberate difference — this starts at version 1 with no migration backlog.
//
// Mocha's file is at version 14 with thirteen migrations, kept so its existing installs upgrade in
// place. No device holds a concord_sync.db yet, so no upgrade path can ever fire: version 1 *is* the
// history. When this app ships and the schema changes, a real migration starts here, and the epoch in
// this database is the promise that nothing pre-existing is touched — so from then on a schema change
// may never be allowed to drop it.
//
// Deliberately not part of music.db. The app's schema version is the most conflict-prone line in a
// fork, and the main database is opened with fallbackToDestructiveMigration, so anything that has to
// survive belongs somewhere else. Joins with the main database happen in Kotlin, by string ids.
package com.mochyy.mochymusic.applesync.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AppleAccountEntity::class,
        AppleEpochEntity::class,
        AppleOwnedEntity::class,
        AppleLinkEntity::class,
        AppleTrackEntity::class,
        AppleSnapshotEntity::class,
        AppleJournalEntity::class,
        ApplePendingOpEntity::class,
        AppleAuditEntity::class,
        AppleLibrarySongEntity::class,
        ApplePlaylistEntity::class,
        AppleRematchEntity::class,
        CoverCacheEntity::class,
        CoverOverrideEntity::class,
        PlaylistMetaEntity::class,
        AppleStateEntity::class,
        PlaylistCollaboratorEntity::class,
        UnmatchedDecisionEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppleSyncDatabase : RoomDatabase() {
    abstract val dao: AppleSyncDao

    companion object {
        const val DB_NAME = "concord_sync.db"

        /**
         * Concord: the first real migration. Version 1 shipped without a backlog because no device
         * could hold an older copy; this one exists because the app has been installed and the table
         * is full of the person's library, so the epoch and the replica must survive it.
         */
        private val Migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `track` ADD COLUMN `youtubeId` TEXT")
                db.execSQL("ALTER TABLE `track` ADD COLUMN `youtubeTitle` TEXT")
            }
        }

        fun newInstance(context: Context): AppleSyncDatabase =
            Room
                .databaseBuilder(context, AppleSyncDatabase::class.java, DB_NAME)
                .addMigrations(Migration1To2)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
