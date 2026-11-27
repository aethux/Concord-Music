// Concord: new. Apple's `artistName` is a display line, not a stored value: it carries double spaces
// ("Talia  Lahoud") and stray padding. Writing that straight into the artist table makes a second
// artist row for a name the library already has, so every credit line that becomes an artist's name
// goes through here first.

package com.mochyy.mochymusic.applesync

/** The name an artist row gets from a song's credit line: one space between words, no padding. */
internal fun creditArtistName(raw: String): String = raw.replace(RunsOfSpaces, " ").trim()

private val RunsOfSpaces = Regex("\\s+")
