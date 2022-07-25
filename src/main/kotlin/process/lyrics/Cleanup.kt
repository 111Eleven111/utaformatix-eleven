package process.lyrics

import model.LyricsType
import model.LyricsType.KanaCv
import model.LyricsType.KanaVcv
import model.LyricsType.RomajiCv
import model.LyricsType.RomajiVcv
import model.LyricsType.Unknown
import model.Track

fun cleanup(tracks: List<Track>, type: LyricsType) =
    when (type) {
        Unknown -> tracks
        RomajiCv -> tracks.cleanup { it.cleanupAsRomajiCV() }
        RomajiVcv -> tracks.cleanup { it.cleanupAsRomajiVCV() }
        KanaCv -> tracks.cleanup { it.cleanupAsKanaCV() }
        KanaVcv -> tracks.cleanup { it.cleanupAsKanaVCV() }
    }

private fun String.cleanupAsRomajiCV(): String {
    if (this.isEmpty()) return this

    var result = this.lowercase()
    result = result.trim()
    result = result.trimStart('?')

    val maxLength = romajis.map { it.length }.maxOrNull() ?: 0
    for (length in maxLength downTo 1) {
        val text = result.take(length)
        if (text.isRomaji) result = text
        break
    }

    return result
}

private fun String.cleanupAsRomajiVCV(): String {
    if (this.isEmpty()) return this

    var result = this.lowercase()
    result = result.trim()

    if (!result.contains(" ")) {
        return this.cleanupAsRomajiCV()
    }

    val blankPos = result.indexOf(" ")
    var body = ""

    val maxLength = romajis.map { it.length }.maxOrNull() ?: 0
    for (length in 1..maxLength) {
        val startPos = blankPos + 1
        val endPos = startPos + length
        if (result.lastIndex < endPos - 1) break
        val text = result.substring(startPos, endPos)
        if (text.isRomaji) body = text
        break
    }

    val prefixChar = result[blankPos - 1]
    if (body.isNotEmpty() && prefixChar.isRomajiTail()) {
        result = "$prefixChar $body"
    }

    return result
}

private fun String.cleanupAsKanaCV(): String {
    if (this.isEmpty()) return this

    var result = this.trim()

    var text: String
    for (index in 0..result.lastIndex) {
        if (index + 2 <= result.lastIndex + 1) {
            text = result.substring(index, index + 2)
            if (!text.isKana) {
                text = result.substring(index, index + 1)
            }
        } else {
            text = result.substring(index, index + 1)
        }
        if (text.isKana) {
            result = text
            break
        }
    }

    result = result.replace("っ", "")

    return result
}

private fun String.cleanupAsKanaVCV(): String {
    if (this.isEmpty()) return this

    var result = this.trim()

    if (!result.contains(" ")) {
        return this.cleanupAsKanaCV()
    }

    val blankPos = result.indexOf(" ")
    val startPos = blankPos + 1
    var body: String

    if (startPos + 2 <= result.lastIndex + 1) {
        body = result.substring(startPos, startPos + 2)
        if (!body.isKana) {
            body = result.substring(startPos, startPos + 1)
        }
    } else {
        body = result.substring(startPos, startPos + 1)
    }

    val prefixChar = result[blankPos - 1]
    if (body.isKana && prefixChar.isRomajiTail()) {
        result = "$prefixChar $body"
    }

    return result
}

private fun List<Track>.cleanup(noteCleanup: (String) -> String) =
    map { track ->
        track.copy(
            notes = track.notes.map { note ->
                note.copy(lyric = noteCleanup(note.lyric))
            }
        )
    }

private val romajiTails = listOf('a', 'i', 'u', 'e', 'o', 'n', '-')

private fun Char.isRomajiTail(): Boolean = romajiTails.contains(this)
