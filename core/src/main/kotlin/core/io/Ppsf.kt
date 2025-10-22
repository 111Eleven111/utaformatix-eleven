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
import kotlinx.serialization.json.JsonElement
import org.w3c.files.Blob
import org.w3c.files.File

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
        val templateText = Resources.ppsfTemplate
        val ppsf = jsonSerializer.decodeFromString(Project.serializer(), templateText)

        // Build new inner project by copying the template and replacing fields we need
        val oldInner = ppsf.ppsf.project

        // tempos
        val tempoSeq =
            project.tempos
                .map { t ->
                    TempoSequenceEvent(
                        curveType = null,
                        tick = t.tickPosition.toInt(),
                        value = (t.bpm * BPM_RATE).toInt(),
                    )
                }
        val constTempoValue = (project.tempos.firstOrNull()?.bpm ?: 120.0)
        val newTempo =
            Tempo(
                const = (constTempoValue * BPM_RATE).toInt(),
                sequence = tempoSeq,
                useSequence = tempoSeq.isNotEmpty(),
            )

        // meters
        val meterSeq =
            project.timeSignatures
                .map { m ->
                    MeterSequenceEvent(
                        denomi = m.denominator,
                        nume = m.numerator,
                        measure = m.measurePosition,
                    )
                }
        val firstMs = project.timeSignatures.firstOrNull()
        val newMeter =
            Meter(
                const =
                    MeterConstValue(
                        denomi = firstMs?.denominator ?: 4,
                        nume = firstMs?.numerator ?: 4,
                    ),
                sequence = meterSeq,
                useSequence = meterSeq.isNotEmpty(),
            )

        // tracks and events - merge with template events to preserve required fields
        val newTracks =
            project.tracks.mapIndexed { idx, t ->
                val templateTrack = oldInner.dvlTrack.getOrNull(idx)
                val templateEvent = templateTrack?.events?.firstOrNull()
                val events =
                    t.notes.map { n ->
                        if (templateEvent != null) {
                            Event(
                                adjustSpeed = templateEvent.adjustSpeed,
                                attackSpeedRate = templateEvent.attackSpeedRate,
                                consonantRate = templateEvent.consonantRate,
                                consonantSpeedRate = templateEvent.consonantSpeedRate,
                                enabled = true,
                                length = n.length,
                                lyric = n.lyric,
                                noteNumber = n.key,
                                noteOffPitEnvelope = templateEvent.noteOffPitEnvelope,
                                noteOnPitEnvelope = templateEvent.noteOnPitEnvelope,
                                portamentoEnvelope = templateEvent.portamentoEnvelope,
                                portamentoType = templateEvent.portamentoType,
                                pos = n.tickOn,
                                isProtected = templateEvent.isProtected,
                                releaseSpeedRate = templateEvent.releaseSpeedRate,
                                symbols = templateEvent.symbols,
                                vclLikeNoteOff = templateEvent.vclLikeNoteOff,
                            )
                        } else {
                            Event(
                                length = n.length,
                                lyric = n.lyric,
                                noteNumber = n.key,
                                pos = n.tickOn,
                            )
                        }
                    }
                DvlTrack(
                    enabled = templateTrack?.enabled ?: true,
                    events = events,
                    mixer = templateTrack?.mixer,
                    name = t.name,
                    parameters = templateTrack?.parameters,
                    pluginOutputBusIndex = templateTrack?.pluginOutputBusIndex,
                    singer = templateTrack?.singer,
                )
            }

        val newInner =
            oldInner.copy(
                dvlTrack = newTracks,
                meter = newMeter,
                tempo = newTempo,
                name = project.name,
            )

        val newRoot =
            ppsf.ppsf.copy(
                project = newInner,
            )
        val newProject =
            ppsf.copy(
                ppsf = newRoot,
            )

        val outJson =
            jsonSerializer.encodeToString(
                Project.serializer(),
                newProject,
            )

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
