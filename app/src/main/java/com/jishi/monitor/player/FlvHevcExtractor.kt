package com.jishi.monitor.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import androidx.media3.common.util.ParsableByteArray
import com.jishi.monitor.core.Logx

/**
 * 支持 HEVC 的 FLV 提取器（技术决策 TD-001）。
 *
 * 背景：EasyNVR 直播为 HTTP-FLV + H.265，而 Media3 的 FlvExtractor 只接受 AVC(codecId=7)，
 * 遇到 codecId=12（HEVC）直接抛 UnsupportedFormatException（本机实测：Video format not supported: 12）。
 *
 * 做法：自行解析 FLV tag，把视频样本按「4 字节长度前缀 NALU」直接交给 Media3/MediaCodec，
 * 与 MP4（hvc1）完全同构。**没有 fMP4 重封装、没有自研转码**，只是补齐官方提取器缺失的 codecId。
 *
 * 实测 FLV 结构（评审-交付/技术决策记录.md）：
 *   video tag = 1c 00 | CTS 3B | 114B hvcC（Main@L5.0、8bit 4:2:0、lengthSizeMinusOne=3、VPS/SPS/PPS 齐备）
 */
class FlvHevcExtractor : Extractor {

    private var output: ExtractorOutput? = null
    private var videoTrack: TrackOutput? = null
    private var audioTrack: TrackOutput? = null
    private var videoFormatSent = false
    private var audioFormatSent = false
    private var tracksEnded = false
    private var headerParsed = false
    private var readCount = 0
    private var videoSampleCount = 0
    private var videoWidth = 0
    private var videoHeight = 0

    /**
     * 时间戳基准。
     * EasyNVR 直播的 FLV 时间戳基值很大且每次连接不同（实测 700617ms / 748686ms / 1065100ms…），
     * 若直接透传，Media3 会按「绝对时间」等待，导致长时间不出画。这里把首个媒体 tag 的时间戳归零。
     */
    private var baseTimestampMs = -1L

    private var buffer = ByteArray(64 * 1024)

    override fun sniff(input: ExtractorInput): Boolean {
        val header = ByteArray(9)
        input.resetPeekPosition()
        if (!input.peekFully(header, 0, 3, true)) return false
        input.resetPeekPosition()
        // 'F' 'L' 'V'
        return header[0] == 0x46.toByte() && header[1] == 0x4C.toByte() && header[2] == 0x56.toByte()
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
    }

    private fun ensureTracks() {
        if (tracksEnded) return
        val o = output ?: return
        if (videoTrack == null) videoTrack = o.track(0, C.TRACK_TYPE_VIDEO)
        if (audioTrack == null) audioTrack = o.track(1, C.TRACK_TYPE_AUDIO)
        // 直播流不可 seek；缺这一句 Media3 的 ProgressiveMediaPeriod 永远不会进入 prepared 状态
        o.seekMap(androidx.media3.extractor.SeekMap.Unseekable(C.TIME_UNSET))
        o.endTracks()
        tracksEnded = true
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!headerParsed) {
            val header = ByteArray(9)
            input.readFully(header, 0, 9)
            val dataOffset = ((header[5].toInt() and 0xFF) shl 24) or
                ((header[6].toInt() and 0xFF) shl 16) or
                ((header[7].toInt() and 0xFF) shl 8) or
                (header[8].toInt() and 0xFF)
            if (dataOffset > 9) input.skipFully(dataOffset - 9)
            // FLV 结构：[9B header][4B PreviousTagSize0][tag1][4B prevSize1][tag2][4B prevSize2]...
            // 这里先跳过 PreviousTagSize0，循环内只读「tag 头 + 数据 + 尾部 4 字节」。
            val prevSize0 = ByteArray(4)
            if (!input.readFully(prevSize0, 0, 4, true)) return Extractor.RESULT_END_OF_INPUT
            headerParsed = true
            ensureTracks()
            Logx.i(TAG, "FLV 头解析完成 dataOffset=" + dataOffset)
        }

        // Tag header (11)
        val tagHeader = ByteArray(11)
        if (!input.readFully(tagHeader, 0, 11, true)) return Extractor.RESULT_END_OF_INPUT
        val tagType = tagHeader[0].toInt() and 0xFF
        val dataSize = ((tagHeader[1].toInt() and 0xFF) shl 16) or
            ((tagHeader[2].toInt() and 0xFF) shl 8) or
            (tagHeader[3].toInt() and 0xFF)
        val tsLow = ((tagHeader[4].toInt() and 0xFF) shl 16) or
            ((tagHeader[5].toInt() and 0xFF) shl 8) or
            (tagHeader[6].toInt() and 0xFF)
        val tsHigh = tagHeader[7].toInt() and 0xFF
        val timestampMs = (tsHigh shl 24) or tsLow

        if (dataSize <= 0) return Extractor.RESULT_CONTINUE
        if (buffer.size < dataSize) buffer = ByteArray(dataSize)
        if (!input.readFully(buffer, 0, dataSize, true)) return Extractor.RESULT_END_OF_INPUT

        if (readCount++ < 12) {
            Logx.i(TAG, "tag type=" + tagType + " size=" + dataSize + " ts=" + timestampMs + " b0=" + (buffer[0].toInt() and 0xFF) + " b1=" + (buffer[1].toInt() and 0xFF))
        }
        when (tagType) {
            18 -> handleScriptData(dataSize)
            9 -> handleVideo(dataSize, timestampMs)
            8 -> handleAudio(dataSize, timestampMs)
            else -> Unit
        }

        // PreviousTagSize (4) 尾部
        val tail = ByteArray(4)
        input.readFully(tail, 0, 4, true)
        return Extractor.RESULT_CONTINUE
    }

    /**
     * onMetaData（AMF0）里带 width/height。
     * 必要性：部分解码器（含雷电模拟器的 OMX.qcom.video.decoder.hevc）在 width/height 缺失时
     * 直接报 IllegalArgumentException: Invalid size(s), width=-1, height=-1（本机实测）。
     */
    private fun handleScriptData(size: Int) {
        val w = findAmfNumber(size, "width")
        val h = findAmfNumber(size, "height")
        if (w != null && h != null && w > 0 && h > 0) {
            videoWidth = w.toInt()
            videoHeight = h.toInt()
            Logx.i(TAG, "onMetaData 解析到分辨率 " + videoWidth + "x" + videoHeight)
        }
    }

    private fun findAmfNumber(size: Int, key: String): Double? {
        val k = key.toByteArray(Charsets.US_ASCII)
        var i = 0
        while (i + k.size + 9 <= size) {
            var match = true
            for (j in k.indices) {
                if (buffer[i + j] != k[j]) { match = false; break }
            }
            if (match && (buffer[i + k.size].toInt() and 0xFF) == 0x00) {
                var bits = 0L
                for (j in 1..8) {
                    bits = (bits shl 8) or (buffer[i + k.size + j].toLong() and 0xFF)
                }
                return Double.fromBits(bits)
            }
            i++
        }
        return null
    }

    private fun handleVideo(size: Int, timestampMs: Int) {
        if (size < 5) return
        val v = videoTrack ?: return
        val frameType = (buffer[0].toInt() and 0xF0) shr 4
        val codecId = buffer[0].toInt() and 0x0F
        if (codecId != FLV_CODEC_HEVC && codecId != FLV_CODEC_AVC) return
        val packetType = buffer[1].toInt() and 0xFF
        val cts = readInt24(buffer, 2)
        val bodyOffset = 5
        val bodySize = size - bodyOffset
        if (bodySize <= 0) return

        if (packetType == 0) {
            // 序列头（解码初始化数据）
            val config = buffer.copyOfRange(bodyOffset, size)
            val mime = if (codecId == FLV_CODEC_HEVC) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
            if (videoWidth <= 0 || videoHeight <= 0) {
                HevcSps.dimensionsFromHvcC(config)?.let { (w, h) ->
                    videoWidth = w
                    videoHeight = h
                    Logx.i(TAG, "SPS 解析到分辨率 " + w + "x" + h)
                }
            }
            val builder = Format.Builder()
                .setSampleMimeType(mime)
                .setInitializationData(listOf(config))
            if (videoWidth > 0 && videoHeight > 0) {
                builder.setWidth(videoWidth).setHeight(videoHeight)
            }
            v.format(builder.build())
            videoFormatSent = true
            Logx.i(TAG, "video 序列头已下发 codecId=" + codecId + " configLen=" + config.size)
            return
        }
        if (packetType != 1 || !videoFormatSent) return

        // 基准必须取「首个样本」而非首个 tag：序列头 tag 的 ts 与真正的样本 ts 可能相差很大
        // （实测序列头 3955ms、首个样本 700617ms），若按 0 起算，播放器要等十几分钟才到第一帧。
        if (baseTimestampMs < 0) {
            baseTimestampMs = timestampMs.toLong()
            Logx.i(TAG, "样本时间戳基准归一化 base=" + baseTimestampMs + "ms")
        }
        val base = baseTimestampMs
        val sampleTimeUs = ((timestampMs - base) + cts) * 1000L
        val flags = if (frameType == 1) C.BUFFER_FLAG_KEY_FRAME else 0
        var offset = 0
        while (offset + 4 <= bodySize) {
            val naluLen = readInt32(buffer, bodyOffset + offset)
            if (naluLen <= 0 || offset + 4 + naluLen > bodySize) break
            offset += 4 + naluLen
        }
        if (offset <= 0) return
        if (videoSampleCount++ % 60 == 0) {
            Logx.i(TAG, "video 样本 #" + videoSampleCount + " bytes=" + offset + " timeUs=" + sampleTimeUs + " key=" + (frameType == 1))
        }
        emit(v, buffer, bodyOffset, offset)
        v.sampleMetadata(sampleTimeUs, flags, offset, 0, null)
    }

    private fun handleAudio(size: Int, timestampMs: Int) {
        if (size < 2) return
        val a = audioTrack ?: return
        val soundFormat = (buffer[0].toInt() and 0xF0) shr 4
        if (soundFormat != FLV_SOUND_AAC) return
        val aacPacketType = buffer[1].toInt() and 0xFF
        val bodyOffset = 2
        val bodySize = size - bodyOffset
        if (bodySize <= 0) return
        if (aacPacketType == 0) {
            val config = buffer.copyOfRange(bodyOffset, size)
            a.format(
                Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_AAC)
                    .setInitializationData(listOf(config))
                    .setChannelCount(1)
                    .setSampleRate(44100)
                    .build()
            )
            audioFormatSent = true
            return
        }
        if (aacPacketType != 1 || !audioFormatSent) return
        if (baseTimestampMs < 0) {
            baseTimestampMs = timestampMs.toLong()
            Logx.i(TAG, "音频样本时间戳基准归一化 base=" + baseTimestampMs + "ms")
        }
        emit(a, buffer, bodyOffset, bodySize)
        a.sampleMetadata((timestampMs - baseTimestampMs) * 1000L, 0, bodySize, 0, null)
    }

    /** Media3 1.3.1 的 TrackOutput 只提供 ParsableByteArray 版 sampleData。 */
    private fun emit(track: TrackOutput, data: ByteArray, offset: Int, length: Int) {
        val pba = ParsableByteArray(data, offset + length)
        pba.setPosition(offset)
        track.sampleData(pba, length)
    }

    private fun readInt24(b: ByteArray, off: Int): Int {
        var v = ((b[off].toInt() and 0xFF) shl 16) or ((b[off + 1].toInt() and 0xFF) shl 8) or (b[off + 2].toInt() and 0xFF)
        if (v and 0x800000 != 0) v -= 0x1000000
        return v
    }

    private fun readInt32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    override fun seek(position: Long, timeUs: Long) {
        // 直播流不支持 seek；回放走 MP4 分片路径
    }

    override fun release() {
        output = null
        videoTrack = null
        audioTrack = null
    }

    companion object {
        private const val TAG = "FlvHevc"
        private const val FLV_CODEC_AVC = 7
        private const val FLV_CODEC_HEVC = 12
        private const val FLV_SOUND_AAC = 10

        /** 供 ProgressiveMediaSource 使用：把本提取器排在默认提取器之前。 */
        val FACTORY = ExtractorsFactory { arrayOf<Extractor>(FlvHevcExtractor()) }
    }
}
