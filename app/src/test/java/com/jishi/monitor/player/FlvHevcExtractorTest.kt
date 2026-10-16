package com.jishi.monitor.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * 用**真实抓取的 EasyNVR 直播 FLV 字节**驱动 FlvHevcExtractor（不联网、不回放）。
 * 样本：m0/evidence/F0-live-head.bin（1.4MB：script tag + hvcC 序列头 + 关键帧 + AAC）
 */
class FlvHevcExtractorTest {

    private class ByteArrayInput(private val data: ByteArray) : ExtractorInput {
        private var pos = 0
        private var peekPos = 0

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (pos >= data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, data.size - pos)
            System.arraycopy(data, pos, target, offset, n)
            pos += n
            return n
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            if (!readFully(target, offset, length, false)) throw IOException("eof")
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (pos + length > data.size) return false
            System.arraycopy(data, pos, target, offset, length)
            pos += length
            return true
        }

        override fun skip(length: Int): Int {
            if (pos >= data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, data.size - pos)
            pos += n
            return n
        }

        override fun skipFully(length: Int) {
            if (!skipFully(length, false)) throw IOException("eof skip")
        }

        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
            if (pos + length > data.size) return false
            pos += length
            return true
        }

        override fun peek(target: ByteArray, offset: Int, length: Int): Int {
            if (pos >= data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, data.size - pos)
            System.arraycopy(data, pos, target, offset, n)
            peekPos = pos + n
            return n
        }

        override fun peekFully(target: ByteArray, offset: Int, length: Int) {
            if (!peekFully(target, offset, length, false)) throw IOException("eof peek")
        }

        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (pos + length > data.size) return false
            System.arraycopy(data, pos, target, offset, length)
            peekPos = pos + length
            return true
        }

        override fun advancePeekPosition(length: Int) {
            if (!advancePeekPosition(length, false)) throw IOException("eof adv")
        }

        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
            if (peekPos + length > data.size) return false
            peekPos += length
            return true
        }

        override fun resetPeekPosition() { peekPos = pos }
        override fun getPeekPosition(): Long = peekPos.toLong()
        override fun getPosition(): Long = pos.toLong()
        override fun getLength(): Long = data.size.toLong()
        override fun <E : Throwable> setRetryPosition(position: Long, e: E) { throw e }
    }

    private class RecordingTrack : TrackOutput {
        var format: Format? = null
        val samples = ArrayList<Long>()
        var keyFrames = 0

        override fun format(format: Format) { if (this.format == null) this.format = format }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int = 0

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {}

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            samples.add(timeUs)
            if (flags and C.BUFFER_FLAG_KEY_FRAME != 0) keyFrames++
        }
    }

    private class RecordingOutput : ExtractorOutput {
        val tracks = HashMap<Int, RecordingTrack>()
        var ended = false
        override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { RecordingTrack() }
        override fun endTracks() { ended = true }
        override fun seekMap(seekMap: SeekMap) {}
    }

    private fun sampleFile(): File {
        val candidates = listOf(
            File("m0/evidence/F0-live-head.bin"),
            File("../m0/evidence/F0-live-head.bin"),
            File("C:/Users/33426/Desktop/jishi/m0/evidence/F0-live-head.bin"),
        )
        return candidates.firstOrNull { it.exists() } ?: error("缺少样本文件 F0-live-head.bin")
    }

    @Test
    fun parsesRealEasyNvrFlvWithHevc() {
        val bytes = sampleFile().readBytes()
        assertTrue("样本太小", bytes.size > 100_000)
        assertEquals('F'.code.toByte(), bytes[0])
        assertEquals('L'.code.toByte(), bytes[1])
        assertEquals('V'.code.toByte(), bytes[2])

        val extractor = FlvHevcExtractor()
        val input = ByteArrayInput(bytes)
        assertTrue("sniff 应识别 FLV", extractor.sniff(input))

        val output = RecordingOutput()
        extractor.init(output)
        val holder = PositionHolder()
        var result = Extractor.RESULT_CONTINUE
        var guard = 0
        while (result == Extractor.RESULT_CONTINUE && guard++ < 200_000) {
            result = try { extractor.read(input, holder) } catch (e: IOException) { Extractor.RESULT_END_OF_INPUT }
        }

        val video = output.tracks[0]!!
        val audio = output.tracks[1]!!
        assertTrue("应结束轨道声明", output.ended)
        assertEquals("视频应为 HEVC", MimeTypes.VIDEO_H265, video.format?.sampleMimeType)
        val init = video.format?.initializationData
        assertTrue("应下发 hvcC 初始化数据", init != null && init.isNotEmpty() && init[0].size > 90)
        assertTrue("应解析出 >=10 个视频样本，实际 " + video.samples.size, video.samples.size >= 10)
        assertTrue("应有关键帧", video.keyFrames >= 1)
        assertTrue("视频时间戳应单调不减", video.samples.zipWithNext().all { (a, b) -> b >= a })
        assertEquals("音频应为 AAC", MimeTypes.AUDIO_AAC, audio.format?.sampleMimeType)
        // 样本抓自「客厅 01」，ffprobe 实测为 2560x1440（SPS 必须精确还原）
        assertEquals("SPS 宽度应为 2560", 2560, video.format?.width)
        assertEquals("SPS 高度应为 1440", 1440, video.format?.height)
        println("[FlvHevcExtractorTest] videoSamples=" + video.samples.size + " keyFrames=" + video.keyFrames +
            " hvcC=" + (init?.get(0)?.size ?: 0) + " audioSamples=" + audio.samples.size +
            " firstTs=" + video.samples.first())
    }
}
