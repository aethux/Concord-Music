// Concord: copied from Mocha applesync/di/AppleSongWritesEntryPoint.kt (apple-sync@c90b8251). Package renamed only.
package com.mochyy.mochymusic.applesync.di

import com.mochyy.mochymusic.applesync.AppleTokens
import com.mochyy.mochymusic.applesync.db.AppleSyncDao
import com.mochyy.mochymusic.db.MusicDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * What one tap on a heart or a library button needs: the tokens for Apple, the sidecar for the guard
 * and the audit, and the app's own database for the row that follows Apple's answer.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppleSongWritesEntryPoint {
    fun dao(): AppleSyncDao
    fun tokens(): AppleTokens
    fun database(): MusicDatabase
}
