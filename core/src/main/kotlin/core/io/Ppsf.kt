package core.io

import core.exception.UnsupportedLegacyPpsfError
import core.external.JsZip
import core.external.JsZipOption
import core.external.Resources
import core.model.ExportResult
import core.model.FeatureConfig
import core.model.Format
import core.model.ImportParams
import core.model.ImportWarning
import core.model.Tempo
import core.model.TimeSignature
import core.process.validateNotes
import core.util.nameWithoutExtension
import core.util.readBinary
import kotlinx.coroutines.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.files.Blob
import org.w3c.files.File
import kotlin.math.max

object Ppsf {
    suspend fun parse(
        file: File,
        params: ImportParams,
    ): core.model.Project {
        val content = readContent(file)
        val warnings = mutableListOf<ImportWarning>()

        val name =
            content.ppsf.project.name
                ?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension

        val timeSignatures =
            content.ppsf.project.meter
                .let { meter ->
                    val first =
                        TimeSignature(
                            measurePosition = 0,
                            numerator = meter.const.nume,
                            denominator = meter.const.denomi,
                        )
                    if (!meter.useSequence) {
                        listOf(first)
                    } else {
                        val sequence =
                            meter.sequence.orEmpty().map { event ->
                                TimeSignature(
                                    measurePosition = event.measure,
                                    numerator = event.nume,
                                    denominator = event.denomi,
                                )
                            }
                        if (sequence.none { it.measurePosition == 0 }) {
                            listOf(first) + sequence
                        } else {
                            sequence
                        }
                    }
                }.takeIf { it.isNotEmpty() } ?: listOf(TimeSignature.default).also {
                warnings.add(ImportWarning.TimeSignatureNotFound)
            }

        val tempos =
            content.ppsf.project.tempo
                .let { tempos: Tempo ->
                    val first =
                        Tempo(
                            tickPosition = 0,
                            bpm = tempos.const.toDouble() / BPM_RATE,
                        )
                    if (!tempos.useSequence) {
                        listOf(first)
                    } else {
                        val sequence =
                            tempos.sequence.orEmpty().map { event ->
                                Tempo(
                                    tickPosition = event.tick.toLong(),
                                    bpm = event.value.toDouble() / BPM_RATE,
                                )
                            }
                        if (sequence.none { it.tickPosition == 0L }) {
                            listOf(first) + sequence
                        } else {
                            sequence
                        }
                    }
                }.takeIf { it.isNotEmpty() } ?: listOf(core.model.Tempo.default).also {
                warnings.add(ImportWarning.TempoNotFound)
            }

        val tracks =
            content.ppsf.project.dvlTrack
                .mapIndexed { i, track ->
                    parseTrack(i, track, params.defaultLyric)
                }

        return core.model.Project(
            format = format,
            inputFiles = listOf(file),
            name = name,
            tracks = tracks,
            timeSignatures = timeSignatures,
            tempos = tempos,
            measurePrefix = 0,
            importWarnings = warnings,
        )
    }

    suspend fun generate(
        project: core.model.Project,
        features: List<FeatureConfig>,
    ): ExportResult {
        // Read template text and work with JsonElements so we preserve the template structure
        val templateText = Resources.ppsfTemplate
        val templateJson = jsonSerializer.parseToJsonElement(templateText)

        // Helper: obtain template defaults for track/event
        val templateProjectJe =
            templateJson
                .jsonObject["ppsf"]
                ?.jsonObject
                ?.get("project")
        val firstTemplateTrackEventDefaults =
            templateProjectJe
                ?.jsonObject
                ?.get("dvl_track")
                ?.jsonArray
                ?.getOrNull(0)
                ?.jsonObject
                ?.get("events")
                ?.jsonArray
                ?.getOrNull(0)
                ?.jsonObject

        // Build tempo JSON
        val tempoSeqJe =
            buildJsonArray {
                project.tempos.forEach { t ->
                    add(
                        buildJsonObject {
                            put("curve_type", JsonPrimitive(null as Int?))
                            put("tick", JsonPrimitive(t.tickPosition.toInt()))
                            put("value", JsonPrimitive((t.bpm * BPM_RATE).toInt()))
                        },
                    )
                }
            }
        val constTempoValue = project.tempos.firstOrNull()?.bpm ?: 120.0
        val newTempoJe =
            buildJsonObject {
                put("const", JsonPrimitive((constTempoValue * BPM_RATE).toInt()))
                if (tempoSeqJe.isNotEmpty()) put("sequence", tempoSeqJe)
                put("use_sequence", JsonPrimitive(tempoSeqJe.isNotEmpty()))
            }

        // Build meter JSON
        val meterSeqJe =
            buildJsonArray {
                project.timeSignatures.forEach { m ->
                    add(
                        buildJsonObject {
                            put("denomi", JsonPrimitive(m.denominator))
                            put("nume", JsonPrimitive(m.numerator))
                            put("measure", JsonPrimitive(m.measurePosition))
                        },
                    )
                }
            }
        val firstMs = project.timeSignatures.firstOrNull()
        val newMeterJe =
            buildJsonObject {
                put(
                    "const",
                    buildJsonObject {
                        put("denomi", JsonPrimitive(firstMs?.denominator ?: 4))
                        put("nume", JsonPrimitive(firstMs?.numerator ?: 4))
                    },
                )
                if (meterSeqJe.isNotEmpty()) put("sequence", meterSeqJe)
                put("use_sequence", JsonPrimitive(meterSeqJe.isNotEmpty()))
            }

        // Build tracks/events by conservatively merging with template events (preserve counts and defaults)
        val templateDvlArray =
            templateProjectJe
                ?.jsonObject
                ?.get("dvl_track")
                ?.jsonArray

        val newDvlTracksJe =
            buildJsonArray {
                project.tracks.forEachIndexed { idx, t ->
                    val templateTrackJeObj = templateDvlArray?.getOrNull(idx)?.jsonObject
                    val templateEventsArray = templateTrackJeObj?.get("events")?.jsonArray
                    val templateEventDefaults =
                        templateEventsArray?.getOrNull(0)?.jsonObject ?: firstTemplateTrackEventDefaults

                    val notes = t.notes

                    val eventsArray =
                        buildJsonArray {
                            if (templateEventsArray != null && templateEventsArray.isNotEmpty()) {
                                val maxCount = max(templateEventsArray.size, notes.size)
                                for (i in 0 until maxCount) {
                                    val tplEv = templateEventsArray.getOrNull(i)?.jsonObject
                                    val note = notes.getOrNull(i)
                                    val base = tplEv ?: templateEventDefaults ?: buildJsonObject {}
                                    val merged =
                                        buildJsonObject {
                                            // copy template defaults
                                            base.entries.forEach { (k, v) -> put(k, v) }
                                            // overwrite with generated values when a note exists
                                            if (note != null) {
                                                put("enabled", JsonPrimitive(true))
                                                put("length", JsonPrimitive(note.length))
                                                put("lyric", JsonPrimitive(note.lyric))
                                                put("note_number", JsonPrimitive(note.key))
                                                put("pos", JsonPrimitive(note.tickOn))
                                                // Set symbols based on lyric: "-" for continuation, else preserve or use default
                                                if (note.lyric == "-") {
                                                    put("symbols", JsonPrimitive("-"))
                                                }
                                            }
                                        }
                                    add(merged)
                                }
                            } else {
                                // No template events: create events from notes using defaults
                                notes.forEach { note ->
                                    val base = templateEventDefaults ?: buildJsonObject {}
                                    val merged =
                                        buildJsonObject {
                                            base.entries.forEach { (k, v) -> put(k, v) }
                                            put("enabled", JsonPrimitive(true))
                                            put("length", JsonPrimitive(note.length))
                                            put("lyric", JsonPrimitive(note.lyric))
                                            put("note_number", JsonPrimitive(note.key))
                                            put("pos", JsonPrimitive(note.tickOn))
                                            // Set symbols based on lyric: "-" for continuation, else preserve or use default
                                            if (note.lyric == "-") {
                                                put("symbols", JsonPrimitive("-"))
                                            }
                                        }
                                    add(merged)
                                }
                            }
                        }

                    val trackObj =
                        buildJsonObject {
                            // copy template track-level defaults if present (except events)
                            templateTrackJeObj?.entries?.forEach { (k, v) ->
                                if (k == "events") return@forEach
                                put(k, v)
                            }
                            put("events", eventsArray)
                            // preserve template track name if present, else use project track name
                            val tplName = templateTrackJeObj?.get("name")
                            if (tplName != null) put("name", tplName) else put("name", JsonPrimitive(t.name ?: ""))
                        }
                    add(trackObj)
                }
            }

        // Compose final project object by starting from the template and replacing keys we need
        val rootMap = templateJson.jsonObject.toMap().toMutableMap()
        val ppsfMap = rootMap["ppsf"]!!.jsonObject.toMap().toMutableMap()
        val projectMap = ppsfMap["project"]!!.jsonObject.toMap().toMutableMap()

        // Preserve existing tempo/meter sequence and use_sequence flags when present in template
        val templateMeterJe = projectMap["meter"]?.jsonObject
        val finalMeterJe =
            if (templateMeterJe != null) {
                // replace const but keep sequence/use_sequence if present
                val constObj =
                    buildJsonObject {
                        put("denomi", JsonPrimitive(firstMs?.denominator ?: 4))
                        put("nume", JsonPrimitive(firstMs?.numerator ?: 4))
                    }
                val merged =
                    buildJsonObject {
                        templateMeterJe.entries.forEach { (k, v) ->
                            if (k == "const") put("const", constObj) else put(k, v)
                        }
                    }
                merged
            } else {
                newMeterJe
            }

        val templateTempoJe = projectMap["tempo"]?.jsonObject
        val finalTempoJe =
            if (templateTempoJe != null) {
                val constVal = (project.tempos.firstOrNull()?.bpm ?: 120.0)
                val constPrim = JsonPrimitive((constVal * BPM_RATE).toInt())
                val merged =
                    buildJsonObject {
                        templateTempoJe.entries.forEach { (k, v) ->
                            if (k == "const") put("const", constPrim) else put(k, v)
                        }
                    }
                merged
            } else {
                newTempoJe
            }

        // Replace fields while preserving unrelated template fields
        projectMap["dvl_track"] = newDvlTracksJe
        // preserve template dvl_track_count if present
        if (projectMap.containsKey("dvl_track_count")) {
            // keep existing
        } else {
            projectMap["dvl_track_count"] = JsonPrimitive(project.tracks.size)
        }
        // preserve template project name if present and non-empty, else use project.name
        val tplNameJe = projectMap["name"]
        if (tplNameJe == null || (tplNameJe is JsonPrimitive && tplNameJe.content.isBlank())) {
            projectMap["name"] = JsonPrimitive(project.name ?: "")
        }
        projectMap["tempo"] = finalTempoJe
        projectMap["meter"] = finalMeterJe

        ppsfMap["project"] = JsonObject(projectMap)
        rootMap["ppsf"] = JsonObject(ppsfMap)

        // Also update GUI notes in gui_settings to match the number of events so the editor shows all notes
        try {
            val guiSettingsJe =
                templateJson
                    .jsonObject["ppsf"]
                    ?.jsonObject
                    ?.get("gui_settings")
                    ?.jsonObject

            val eventTracksArray =
                guiSettingsJe
                    ?.get("track-editor")
                    ?.jsonObject
                    ?.get("event-tracks")
                    ?.jsonArray

            val firstGuiTrackJe = eventTracksArray?.getOrNull(0)?.jsonObject
            val templateGuiNote =
                firstGuiTrackJe
                    ?.get("notes")
                    ?.jsonArray
                    ?.getOrNull(0)
                    ?.jsonObject

            if (firstGuiTrackJe != null && templateGuiNote != null) {
                // read generated events for first track (safe access)
                val generatedEvents =
                    ppsfMap["project"]
                        ?.jsonObject
                        ?.get("dvl_track")
                        ?.jsonArray
                        ?.getOrNull(0)
                        ?.jsonObject
                        ?.get("events")
                        ?.jsonArray
                        ?: buildJsonArray {}

                val newGuiNotes =
                    buildJsonArray {
                        generatedEvents.forEachIndexed { evIndex, evJe ->
                            val evObj = evJe.jsonObject
                            val syllableLyric = evObj["lyric"]?.jsonPrimitive?.content ?: ""
                            val symbolsText =
                                evObj["symbols"]?.jsonPrimitive?.content
                                    ?: if (syllableLyric == "-") {
                                        "-"
                                    } else {
                                        templateGuiNote["syllables"]
                                            ?.jsonArray
                                            ?.getOrNull(0)
                                            ?.jsonObject
                                            ?.get("symbols-text")
                                            ?.jsonPrimitive
                                            ?.content
                                            ?: ""
                                    }

                            val guiNote =
                                buildJsonObject {
                                    // copy template GUI note defaults
                                    templateGuiNote.entries.forEach { (k, v) -> put(k, v) }
                                    // override index/length/portamento and syllables
                                    put("event_index", JsonPrimitive(evIndex))
                                    put("length", evObj["length"] ?: JsonPrimitive(0))
                                    // portamento values taken from event's portamento_envelope if present
                                    val portEnv = evObj["portamento_envelope"]?.jsonObject
                                    if (portEnv != null) {
                                        put("portamento_length", portEnv["length"] ?: JsonPrimitive(0))
                                        put("portamento_offset", portEnv["offset"] ?: JsonPrimitive(0))
                                    }
                                    put(
                                        "syllables",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("footer-text", JsonPrimitive(""))
                                                    put("header-text", JsonPrimitive(""))
                                                    put("is-list-end", JsonPrimitive(true))
                                                    put("is-list-top", JsonPrimitive(true))
                                                    put("is-word-end", JsonPrimitive(true))
                                                    put("is-word-top", JsonPrimitive(true))
                                                    put("lyric-text", JsonPrimitive(syllableLyric))
                                                    put("symbols-text", JsonPrimitive(symbolsText))
                                                },
                                            )
                                        },
                                    )
                                }
                            add(guiNote)
                        }
                    }

                // set the notes array into the gui settings inside rootMap
                val rootGuiMap = rootMap["ppsf"]!!.jsonObject.toMutableMap()
                val rootTrackEditorMap = rootGuiMap["gui_settings"]!!.jsonObject.toMutableMap()
                val rootEventTracks = rootTrackEditorMap["track-editor"]!!.jsonObject.toMutableMap()
                val etArray = rootEventTracks["event-tracks"]!!.jsonArray.toMutableList()
                val firstTrackMap = etArray.getOrNull(0)?.jsonObject?.toMutableMap() ?: mutableMapOf()
                firstTrackMap["notes"] = newGuiNotes
                etArray[0] = JsonObject(firstTrackMap)
                rootEventTracks["event-tracks"] = JsonArray(etArray)
                rootTrackEditorMap["track-editor"] = JsonObject(rootEventTracks)
                rootGuiMap["gui_settings"] = JsonObject(rootTrackEditorMap)
                rootMap["ppsf"] = JsonObject(rootGuiMap)
            }
        } catch (_: Throwable) {
            // If anything goes wrong, keep the template gui_settings as-is
        }

        val finalJson = JsonObject(rootMap)
        val outJson = jsonSerializer.encodeToString(JsonElement.serializer(), finalJson)

        // DEBUG: print a truncated preview of the generated PPSF JSON in the browser console
        try {
            println("[Ppsf.generate] ppsf.json preview: ${'$'}{outJson.take(2000)}")
        } catch (_: Throwable) {
        }

        val zip = JsZip()
        zip.file(JSON_PATH, outJson)
        val option =
            JsZipOption().also {
                it.type = "blob"
                it.mimeType = "application/octet-stream"
            }
        val blob = zip.generateAsync(option).await() as Blob
        val name = format.getFileName(project.name)
        return ExportResult(
            blob,
            name,
            listOf(),
        )
    }

    private fun parseTrack(
        index: Int,
        dvlTrack: DvlTrack,
        defaultLyric: String,
    ): core.model.Track {
        val name = dvlTrack.name ?: "Track ${index + 1}"
        val notes =
            dvlTrack.events.filter { it.enabled != false }.map {
                core.model.Note(
                    id = 0,
                    key = it.noteNumber,
                    lyric = it.lyric?.takeUnless { lyric -> lyric.isBlank() } ?: defaultLyric,
                    tickOn = it.pos,
                    tickOff = it.pos + it.length,
                )
            }
        return core.model
            .Track(
                id = index,
                name = name,
                notes = notes,
            ).validateNotes()
    }

    private suspend fun readContent(file: File): Project {
        val binary = file.readBinary()
        val zip =
            runCatching { JsZip().loadAsync(binary).await() }.getOrElse {
                throw UnsupportedLegacyPpsfError()
            }
        val vprEntry = zip.file(JSON_PATH)
        val text = requireNotNull(vprEntry).async("string").await() as String
        return jsonSerializer.decodeFromString(Project.serializer(), text)
    }

    private val jsonSerializer =
        Json {
            isLenient = true
            ignoreUnknownKeys = true
        }

    private const val BPM_RATE = 10000.0
    private const val JSON_PATH = "ppsf.json"

    @Serializable
    private data class Project(
        @SerialName("ppsf") val ppsf: Root,
    )

    @Serializable
    private data class Root(
        @SerialName("app_ver") val appVer: String,
        @SerialName("gui_settings") val guiSettings: JsonElement? = null,
        @SerialName("ppsf_ver") val ppsfVer: String,
        @SerialName("project") val project: InnerProject,
    )

    @Serializable
    private data class InnerProject(
        @SerialName("app_ver") val appVer: String? = null,
        @SerialName("audio_track") val audioTrack: JsonElement? = null,
        @SerialName("block_size") val blockSize: JsonElement? = null,
        @SerialName("dvl_track") val dvlTrack: List<DvlTrack> = listOf(),
        @SerialName("loop_point") val loopPoint: JsonElement? = null,
        @SerialName("meter") val meter: Meter,
        @SerialName("metronome") val metronome: JsonElement? = null,
        @SerialName("name") val name: String? = null,
        @SerialName("sampling_rate") val samplingRate: Int,
        @SerialName("singer_table") val singerTable: JsonElement? = null,
        @SerialName("tempo") val tempo: Tempo,
        @SerialName("vocaloid_track") val vocaloidTrack: JsonElement? = null,
    )

    @Serializable
    private data class DvlTrack(
        @SerialName("enabled") val enabled: Boolean? = null,
        @SerialName("events") val events: List<Event> = listOf(),
        @SerialName("mixer") val mixer: JsonElement? = null,
        @SerialName("name") val name: String? = null,
        @SerialName("parameters") val parameters: JsonElement? = null,
        @SerialName("plugin_output_bus_index") val pluginOutputBusIndex: Int? = null,
        @SerialName("singer") val singer: JsonElement? = null,
    )

    @Serializable
    private data class Meter(
        @SerialName("const") val const: MeterConstValue,
        @SerialName("sequence") val sequence: List<MeterSequenceEvent>? = null,
        @SerialName("use_sequence") val useSequence: Boolean,
    )

    @Serializable
    private data class Tempo(
        @SerialName("const") val const: Int,
        @SerialName("sequence") val sequence: List<TempoSequenceEvent>? = null,
        @SerialName("use_sequence") val useSequence: Boolean,
    )

    @Serializable
    private data class Event(
        @SerialName("adjust_speed") val adjustSpeed: Boolean? = null,
        @SerialName("attack_speed_rate") val attackSpeedRate: JsonElement? = null,
        @SerialName("consonant_rate") val consonantRate: JsonElement? = null,
        @SerialName("consonant_speed_rate") val consonantSpeedRate: JsonElement? = null,
        @SerialName("enabled") val enabled: Boolean? = null,
        @SerialName("length") val length: Long,
        @SerialName("lyric") val lyric: String? = null,
        @SerialName("note_number") val noteNumber: Int,
        @SerialName("note_off_pit_envelope") val noteOffPitEnvelope: JsonElement? = null,
        @SerialName("note_on_pit_envelope") val noteOnPitEnvelope: JsonElement? = null,
        @SerialName("portamento_envelope") val portamentoEnvelope: JsonElement? = null,
        @SerialName("portamento_type") val portamentoType: JsonElement? = null,
        @SerialName("pos") val pos: Long,
        @SerialName("protected") val isProtected: Boolean? = null,
        @SerialName("release_speed_rate") val releaseSpeedRate: JsonElement? = null,
        @SerialName("symbols") val symbols: String? = null,
        @SerialName("vcl_like_note_off") val vclLikeNoteOff: JsonElement? = null,
    )

    @Serializable
    private data class MeterConstValue(
        @SerialName("denomi") val denomi: Int,
        @SerialName("nume") val nume: Int,
    )

    @Serializable
    private data class MeterSequenceEvent(
        @SerialName("denomi") val denomi: Int,
        @SerialName("nume") val nume: Int,
        @SerialName("measure") val measure: Int,
    )

    @Serializable
    private data class TempoSequenceEvent(
        @SerialName("curve_type") val curveType: Int? = null,
        @SerialName("tick") val tick: Int,
        @SerialName("value") val value: Int,
    )

    private val format = Format.Ppsf
}
