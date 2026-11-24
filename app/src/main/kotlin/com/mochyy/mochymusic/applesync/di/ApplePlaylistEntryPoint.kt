// Concord: copied from Mocha applesync/di/ApplePlaylistEntryPoint.kt (apple-sync@c90b8251). Package renamed only.
package com.mochyy.mochymusic.applesync.di

import com.mochyy.mochymusic.applesync.ApplePlaylistLinker
import com.mochyy.mochymusic.applesync.AppleTokens
import com.mochyy.mochymusic.applesync.db.AppleSyncDao
import com.mochyy.mochymusic.db.MusicDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** What a playlist menu needs, without threading a ViewModel through the menu. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ApplePlaylistEntryPoint {
    fun dao(): AppleSyncDao
    fun tokens(): AppleTokens
    fun database(): MusicDatabase
}
