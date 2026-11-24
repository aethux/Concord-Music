// Concord: new — one Apple detail page, held on disk so a second open paints before the network.
//
// Cider keeps the same thing in IndexedDB under `content/<id>` (cider/client-architecture.md:83),
// as the raw API response. This is that row: the body is what Apple sent, so a hit and a miss go
// through the same parsers and there is no second model to keep in step.
package com.mochyy.mochymusic.applesync.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "page_cache")
data class PageCacheEntity(
    /** `kind|remoteId|paramsHash` — built by `ApplePageCache.pageKey`, never by hand. */
    @PrimaryKey val cacheKey: String,
    /** artist / album / playlist / home: picks the lifetime. */
    val kind: String,
    /** The Apple id in the path, so a write can drop every variant of one page at once. */
    val remoteId: String,
    /** sha-256 of the sorted query: `?views=…` and `?extend=…` never share a row. */
    val paramsHash: String,
    /** The response body, written back from the tree the client already parsed. */
    val body: String,
    val fetchedAt: Long,
    /** When a page was last drawn from this row — what the size cap evicts by. */
    val lastUsedAt: Long,
)
