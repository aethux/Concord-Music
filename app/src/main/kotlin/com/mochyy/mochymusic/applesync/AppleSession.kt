// Concord: one place that knows the Apple session has lapsed. Every read and write already turns a
// 401 into `AppleSyncException(signedOut = true)`, but each caller only toasted its own message, so a
// lapsed session looked like a failed sync and nothing pointed at the way back in. This flag is set
// where a sync fails that way, cleared when a sync succeeds or new tokens are saved, and read by the
// Import & sync page's account row.
package com.mochyy.mochymusic.applesync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object AppleSession {

    private val _expired = MutableStateFlow(false)
    val expired: StateFlow<Boolean> = _expired

    /** A sync that ended with Apple refusing the token (401). */
    fun onFailure(error: Throwable) {
        if (error is AppleSyncException && error.signedOut) _expired.value = true
    }

    /** A sync that read the library, or fresh tokens: the session is good again. */
    fun clear() {
        _expired.value = false
    }
}
