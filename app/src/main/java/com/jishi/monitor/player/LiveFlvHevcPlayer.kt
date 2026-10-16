package com.jishi.monitor.player

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.jishi.monitor.core.Logx
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

/**
 * 直播播放器（HTTP-FLV + H.265）。
 *
 * 技术决策 TD-001：App 内自足，不引入原生内核、不做重封装。
 * 实现要点来自已验证的参考实现（晚风监控-backup FlvHevcPlayer）：
 *   1) 自行解析 FLV tag；
 *   2) **csd-0 用 Annex-B 的 VPS/SPS/PPS**（不是原始 hvcC）；
 *   3) 样本由 4 字节长度前缀转为 Annex-B 起始码；
 *   4) 同步 MediaCodec 直接渲到 Surface，按首帧 PTS 归零。
 * 该组合在小米/EasyNVR 的 HEVC-FLV 上已验证可出画；Media3 的渐进式管线对本流不出帧。
 */
class LiveFlvHevcPlayer(private val listener: LiveKernel.Listener) : LiveKernel {

    // 备用内核（TD-002 回滚路径）：渲染面同样使用 TextureView，保证 Compose 缩放可用
    private var renderView: android.view.TextureView? = null
    @Volatile private var pendingUrl: String? = null
    @Volatile private var pendingToken: String? = null
    @Volatile private var surface: Surface? = null

    override fun createRenderView(ctx: android.content.Context): android.view.View {
        val tv = android.view.TextureView(ctx)
        tv.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                // 旧的 Surface 先释放，再用新的（TextureView 重建时的正确做法）
                try { surface?.release() } catch (t: Throwable) { }
                surface = Surface(st)
                maybeStart()
            }

            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) = Unit

            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                try { surface?.release() } catch (t: Throwable) { }
                surface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) = Unit
        }
        renderView = tv
        return tv
    }

    override fun start(url: String, token: String?, renderView: android.view.View) {
        pendingUrl = url
        pendingToken = token
        renderView as? android.view.TextureView
        maybeStart()
    }

    private fun maybeStart() {
        val u = pendingUrl ?: return
        val s = surface ?: return
        start(u, pendingToken, s)
    }

    @Volatile private var stopped = false
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var codec: MediaCodec? = null
    private var worker: Thread? = null
    @Volatile private var playing = false
    @Volatile private var firstFrameSent = false
    @Volatile private var outWidth = 0
    @Volatile private var outHeight = 0
    @Volatile private var paused = false
    @Volatile private var muted = false

    private var audioCodec: MediaCodec? = null

    // ---- 音频独立线程：与视频解码解耦 ----
    // 背景：原实现把 AAC 解码与 AudioTrack 写入放在视频解码循环里，track.write 阻塞会把音频拖饿，
    // 表现就是"声音一卡一卡 + 电流声"。改为有界队列 + 专用线程，队列满时丢最旧帧（宁可丢帧不可延迟累积）。
    private class AudioFrame(val data: ByteArray, val ptsUs: Long)

    private val audioQueue = java.util.concurrent.LinkedBlockingDeque<AudioFrame>(48)
    private var audioWorker: Thread? = null
    @Volatile private var audioWorkerStop = false
    @Volatile private var audioWriteWarned = false
    @Volatile private var audioTagCount = 0
    @Volatile private var audioOutCount = 0
    @Volatile private var audioStatStart = 0L
    @Volatile private var audioBytesWindow = 0
    @Volatile private var audioFramesWindow = 0
    private var audioTrack: android.media.AudioTrack? = null
    private var audioSampleRate = 44100
    private var audioChannels = 1
    @Volatile private var audioTrackRate = 0
    @Volatile private var audioTrackChannels = 0

    override fun setMuted(value: Boolean) {
        muted = value
        try { audioTrack?.setVolume(if (value) 0f else 1f) } catch (t: Throwable) { }
    }

    override fun isPaused(): Boolean = paused

    override fun togglePause() {
        paused = !paused
        if (paused) {
            try { audioTrack?.pause() } catch (t: Throwable) { }
        } else {
            try { audioTrack?.play() } catch (t: Throwable) { }
        }
    }

    override fun isPlaying(): Boolean = playing

    fun start(url: String, token: String?, surface: Surface) {
        stop()
        stopped = false
        playing = false
        firstFrameSent = false
        generation += 1
        val myGen = generation
        worker = Thread({ run(url, token, surface, myGen) }, "live-flv-hevc").apply { isDaemon = true; start() }
    }

    override fun stop() {
        generation += 1   // 让正在收尾的旧 worker 失效
        stopped = true
        paused = false
        pendingUrl = null
        // 注意：不要在这里释放 Surface——渲染面会被反复复用（重试/换通道都是同一个 TextureView），
        // 释放掉会让下一次 start 渲染到已释放的 Surface 上，表现为"首帧永远超时"。
        // Surface 的生命周期由 TextureView 的 onSurfaceTextureAvailable/Destroyed 管理。
        try { audioCodec?.stop() } catch (t: Throwable) { }
        try { audioCodec?.release() } catch (t: Throwable) { }
        audioCodec = null
        try { audioTrack?.stop() } catch (t: Throwable) { }
        try { audioTrack?.release() } catch (t: Throwable) { }
        audioTrack = null
        try { connection?.disconnect() } catch (t: Throwable) { }
        connection = null
        try { codec?.stop() } catch (t: Throwable) { }
        try { codec?.release() } catch (t: Throwable) { }
        codec = null
        playing = false
        worker?.interrupt()
        worker = null
    }

    /** 会话代际：每次 start 自增，用于让旧 worker 的收尾失效（避免竞态清掉新会话）。 */
    @Volatile private var generation = 0

    private fun run(url: String, token: String?, surface: Surface, myGen: Int) {
        var decoder: MediaCodec? = null
        try {
            val http = URL(url).openConnection() as HttpURLConnection
            connection = http
            http.requestMethod = "GET"
            http.connectTimeout = 15000
            http.readTimeout = 30000
            http.useCaches = false
            http.setRequestProperty("Accept", "video/x-flv, */*")
            if (!token.isNullOrEmpty()) http.setRequestProperty("Authorization", "Bearer " + token)
            val status = http.responseCode
            if (status < 200 || status >= 300) throw IOException("stream http " + status)
            val input = http.inputStream
            readFlvHeader(input)

            var lengthSize = 4
            var firstTimestamp = Long.MIN_VALUE
            var lastPtsUs = 0L
            val tagHeader = ByteArray(11)
            var frameCount = 0
            val bufferInfo = MediaCodec.BufferInfo()

            while (!stopped && readFully(input, tagHeader, 0, tagHeader.size)) {
                val type = tagHeader[0].toInt() and 0xFF
                val size = readUInt24(tagHeader, 1)
                val timestamp = readUInt24(tagHeader, 4).toLong() or ((tagHeader[7].toInt() and 0xFF).toLong() shl 24)
                if (size <= 0 || size > 16 * 1024 * 1024) throw IOException("invalid FLV tag size " + size)
                val payload = ByteArray(size)
                if (!readFully(input, payload, 0, size)) break
                val previousTagSize = ByteArray(4)
                if (!readFully(input, previousTagSize, 0, 4)) break

                while (paused && !stopped) {
                    try { Thread.sleep(50) } catch (t: InterruptedException) { break }
                }
                if (stopped) break

                if (type == AUDIO_TAG && payload.size >= 2) {
                    handleAudioTag(payload)
                    continue
                }
                if (type != VIDEO_TAG || payload.size < 5) continue
                if ((payload[0].toInt() and 0x0F) != CODEC_HEVC) continue
                val packetType = payload[1].toInt() and 0xFF
                val compositionTime = readSigned24(payload, 2)

                if (packetType == 0) {
                    val config = parseHevcConfiguration(payload, 5) ?: throw IOException("missing HEVC configuration")
                    lengthSize = config.first
                    if (decoder == null) {
                        Logx.i(TAG, "HEVC csd(annexB)=" + config.second.size + "B lengthSize=" + lengthSize)
                        decoder = createDecoder(surface, config.second)
                        codec = decoder
                    }
                    continue
                }
                if (packetType != 1 || decoder == null) continue
                if (stopped) break

                val accessUnit = toAnnexB(payload, 5, payload.size - 5, lengthSize)
                if (accessUnit.isEmpty()) continue

                if (firstTimestamp == Long.MIN_VALUE) firstTimestamp = timestamp
                var ptsUs = ((timestamp - firstTimestamp) + compositionTime) * 1000L
                if (ptsUs < 0) ptsUs = 0
                if (ptsUs <= lastPtsUs && lastPtsUs > 0) ptsUs = lastPtsUs + 1
                lastPtsUs = ptsUs

                queueAndDrain(decoder, accessUnit, ptsUs, bufferInfo)
                frameCount++
                if (!playing) {
                    playing = true
                    Logx.i(TAG, "直播开始出帧")
                }
            }
            if (!stopped) listener.onEnded()
        } catch (t: Throwable) {
            if (!stopped) {
                Logx.e(TAG, "直播解码异常: " + t.javaClass.simpleName + " " + (t.message ?: ""))
                listener.onError(t.message ?: "stream decode failed")
            }
        } finally {
            // 只有"当前代际"的 worker 才允许清理共享状态：
            // 否则旧 worker 的收尾会把刚启动的新会话（codec/audio/connection）一起清掉，
            // 表现为"首帧超时重连后立刻又断"（代码核查 P1 竞态）。
            if (generation == myGen) {
                stopped = true
                playing = false
                try { decoder?.stop() } catch (t: Throwable) { }
                try { decoder?.release() } catch (t: Throwable) { }
                codec = null
                releaseAudio()
                try { connection?.disconnect() } catch (t: Throwable) { }
                connection = null
            } else {
                try { decoder?.stop() } catch (t: Throwable) { }
                try { decoder?.release() } catch (t: Throwable) { }
            }
        }
    }

    /**
     * 音频：FLV tag type=8 + soundFormat=10（AAC）。
     * packetType 0 = AudioSpecificConfig（建解码器与 AudioTrack），1 = 原始 AAC 帧。
     * 直播"静音"仅控制本地播放音量，与语音对讲无关（本项目不申请麦克风权限）。
     */
    private fun handleAudioTag(payload: ByteArray) {
        val soundFormat = (payload[0].toInt() and 0xF0) shr 4
        if (soundFormat != SOUND_AAC || payload.size < 3) return
        val packetType = payload[1].toInt() and 0xFF
        if (packetType == 0) {
            val config = payload.copyOfRange(2, payload.size)
            try {
                releaseAudio()
                val (rate, channels) = parseAacConfig(config)
                audioSampleRate = rate
                audioChannels = channels
                val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels)
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(config))
                val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                dec.configure(fmt, null, null, 0)
                dec.start()
                audioCodec = dec
                // AudioTrack 延迟到解码器给出「真实输出参数」后再建，避免 CSD 推算的声道数
                // 与实际 PCM 不一致（那会把双声道数据当单声道播，听感就是电流声+卡顿）
                audioSampleRate = rate
                audioChannels = channels
                audioCodec = dec
                startAudioWorker()
                Logx.i(TAG, "AAC 解码器就绪（等待真实输出参数）CSD rate=" + rate + " ch=" + channels)
            } catch (t: Throwable) {
                Logx.w(TAG, "AAC 音频初始化失败: " + t.javaClass.simpleName)
                releaseAudio()
            }
            return
        }
        if (packetType != 1) return
        // AudioTrack 是延迟创建的（等解码器真实参数），这里只要求解码器就绪
        if (audioCodec == null) return
        // 入队即返回：绝不在这里阻塞视频解码线程
        audioTagCount++
        if (audioTagCount % 25 == 1) Logx.i(TAG, "收到 AAC 帧 #" + audioTagCount + " size=" + (payload.size - 2))
        val frame = AudioFrame(payload.copyOfRange(2, payload.size), 0L)
        if (!audioQueue.offer(frame)) {
            audioQueue.pollFirst()   // 丢弃最旧帧，控制延迟
            audioQueue.offer(frame)
        }
    }

    /** 按解码器真实输出参数建 AudioTrack；参数变化才重建。 */
    private fun ensureAudioTrack(rate: Int, channels: Int) {
        val mask = if (channels <= 1) android.media.AudioFormat.CHANNEL_OUT_MONO else android.media.AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = android.media.AudioTrack.getMinBufferSize(
            rate, mask, android.media.AudioFormat.ENCODING_PCM_16BIT,
        ).let { if (it <= 0) 4096 else it }
        val target = maxOf(minBuf * 2, (rate * channels * 2 * 300) / 1000)
        val cur = audioTrack
        if (cur != null && audioTrackRate == rate && audioTrackChannels == channels &&
            cur.state == android.media.AudioTrack.STATE_INITIALIZED
        ) {
            return
        }
        try { cur?.pause() } catch (t: Throwable) { }
        try { cur?.flush() } catch (t: Throwable) { }
        try { cur?.release() } catch (t: Throwable) { }
        audioTrack = null
        val track = android.media.AudioTrack.Builder()
            .setAudioFormat(
                android.media.AudioFormat.Builder()
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(mask)
                    .build()
            )
            .setBufferSizeInBytes(target)
            .setTransferMode(android.media.AudioTrack.MODE_STREAM)
            .build()
        track.setVolume(if (muted) 0f else 1f)
        track.play()
        audioTrack = track
        audioTrackRate = rate
        audioTrackChannels = channels
        Logx.i(TAG, "AudioTrack 就绪 rate=" + rate + " ch=" + channels + " 缓冲=" + track.bufferSizeInFrames + " 帧")
    }

    /** 音频线程：解码 AAC → 写 AudioTrack。静音只调音量，仍持续写入，避免缓冲枯竭产生爆音。 */
    private fun startAudioWorker() {
        if (audioWorker != null) return
        audioWorkerStop = false
        val t = Thread {
            // 关键：音频线程必须用 URGENT_AUDIO，否则被视频软解抢占 → AudioTrack 欠载 → 断续/电流声
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (e: Throwable) { }
            while (!audioWorkerStop && !stopped) {
                // 积压过多说明消费跟不上：丢掉旧帧，避免音频无限滞后（音画不同步会像"卡"）
                while (audioQueue.size > 12) audioQueue.pollFirst()
                val frame = try {
                    audioQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    null
                } ?: continue
                try {
                    feedAudio(frame)
                } catch (e: Throwable) {
                    Logx.w(TAG, "音频帧处理失败: " + e.javaClass.simpleName)
                }
            }
        }
        t.name = "jishi-audio"
        t.priority = Thread.NORM_PRIORITY + 2
        audioWorker = t
        t.start()
    }

    private fun feedAudio(frame: AudioFrame) {
        val dec = audioCodec ?: return
        // 注意：AudioTrack 是解码器给出真实参数后才创建的，这里不能用它做前置判空，
        // 否则永远喂不进输入 → 永远不产生 INFO_OUTPUT_FORMAT_CHANGED（自锁）。
        try {
            val info = MediaCodec.BufferInfo()
            val inIndex = dec.dequeueInputBuffer(10_000)
            if (inIndex < 0) return
            val input = dec.getInputBuffer(inIndex) ?: return
            input.clear()
            input.put(frame.data, 0, frame.data.size)
            dec.queueInputBuffer(inIndex, 0, frame.data.size, frame.ptsUs, 0)

            var guard = 0
            while (!audioWorkerStop && guard++ < 8) {
                val outIndex = dec.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) return
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Logx.i(TAG, "AAC 输出格式变化，准备建 AudioTrack")
                    // 以解码器实际输出为准重建 AudioTrack（采样率/声道数可能不同于 CSD 推算）
                    val of = dec.outputFormat
                    val sr = if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) of.getInteger(MediaFormat.KEY_SAMPLE_RATE) else audioSampleRate
                    val cc = if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) of.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else audioChannels
                    ensureAudioTrack(sr, cc)
                    continue
                }
                if (outIndex < 0) continue
                val buf = dec.getOutputBuffer(outIndex)
                audioOutCount++
                if (audioOutCount == 1) Logx.i(TAG, "AAC 首帧解码输出 size=" + info.size)
                if (buf != null && info.size > 0) {
                    val pcm = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    buf.get(pcm)
                    // 静音也照常写入，仅音量为 0（避免缓冲枯竭后恢复时的爆音/电流声）
                    val track = audioTrack
                    audioBytesWindow += pcm.size
                    audioFramesWindow++
                    val nowA = System.currentTimeMillis()
                    if (audioStatStart == 0L) audioStatStart = nowA
                    if (nowA - audioStatStart >= 3000) {
                        val under = try { track?.underrunCount ?: -1 } catch (e: Throwable) { -1 }
                        Logx.i(
                            TAG,
                            "音频写入 " + audioFramesWindow + " 帧 / " + (audioBytesWindow / 1024) + " KB（3 秒）；" +
                                "队列积压=" + audioQueue.size + "；欠载累计=" + under,
                        )
                        audioStatStart = nowA
                        audioBytesWindow = 0
                        audioFramesWindow = 0
                    }
                    if (track == null) {
                        dec.releaseOutputBuffer(outIndex, false)
                        continue
                    }
                    try {
                        val written = track.write(pcm, 0, pcm.size)
                        if (written < 0 && !audioWriteWarned) {
                            audioWriteWarned = true
                            Logx.w(TAG, "AudioTrack 写入失败 code=" + written)
                        }
                    } catch (t: Throwable) {
                        // 写入异常（多半是 track 正在被重建）：丢弃该 track，等下一次格式回调重建
                        if (!audioWriteWarned) {
                            audioWriteWarned = true
                            Logx.w(TAG, "AudioTrack 写入异常 " + t.javaClass.simpleName + "（将重建）")
                        }
                        try { track.release() } catch (e: Throwable) { }
                        if (audioTrack === track) {
                            audioTrack = null
                            audioTrackRate = 0
                            audioTrackChannels = 0
                        }
                    }
                }
                dec.releaseOutputBuffer(outIndex, false)
            }
        } catch (t: Throwable) {
            Logx.w(TAG, "音频写入失败: " + t.javaClass.simpleName)
        }
    }

    private fun releaseAudio() {
        // 先停音频线程再释放解码器/AudioTrack，避免线程用到已释放对象
        audioWorkerStop = true
        audioWorker?.interrupt()
        audioWorker = null
        audioQueue.clear()
        try { audioCodec?.stop() } catch (t: Throwable) { }
        try { audioCodec?.release() } catch (t: Throwable) { }
        audioCodec = null
        try { audioTrack?.stop() } catch (t: Throwable) { }
        try { audioTrack?.release() } catch (t: Throwable) { }
        audioTrack = null
    }

    /** 从 AudioSpecificConfig 解析采样率与声道数。 */
    private fun parseAacConfig(config: ByteArray): Pair<Int, Int> {
        if (config.size < 2) return 44100 to 1
        val frequencyIndex = ((config[0].toInt() and 0x07) shl 1) or ((config[1].toInt() and 0x80) ushr 7)
        val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        val rate = rates.getOrElse(frequencyIndex) { 44100 }
        val channelConfig = (config[1].toInt() and 0x78) ushr 3
        val channels = if (channelConfig == 1) 1 else 2
        return rate to channels
    }

    /** 把 Annex-B 码流按起始码切成 NAL 列表（不含起始码）。 */
    private fun splitNals(data: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        var start = -1
        while (i + 3 <= data.size) {
            val isStart = data[i].toInt() == 0 && data[i + 1].toInt() == 0 &&
                (data[i + 2].toInt() == 1 || (i + 4 <= data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1))
            if (isStart) {
                val scLen = if (data[i + 2].toInt() == 1) 3 else 4
                if (start >= 0) out.add(data.copyOfRange(start, i))
                start = i + scLen
                i += scLen
            } else {
                i++
            }
        }
        if (start in 0 until data.size) out.add(data.copyOfRange(start, data.size))
        return out
    }

    private fun createDecoder(surface: Surface, csd: ByteArray): MediaCodec {
        // 优先用 SPS 解析出的真实分辨率：占位尺寸会让部分实现走内部缩放路径，实测明显掉帧。
        val real = try {
            // csd 是 Annex-B（start code 分隔），HevcSps 面向 hvcC，此处按 NAL 直接找 SPS
            val sps = splitNals(csd).firstOrNull { (it[0].toInt() and 0x7E) shr 1 == 33 }
            if (sps != null) HevcSps.dimensionsFromAnnexBNal(sps) else null
        } catch (t: Throwable) {
            null
        }
        val w = real?.first ?: 1920
        val h = real?.second ?: 1080
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h)
        Logx.i(TAG, "解码器配置 " + w + "x" + h + (if (real == null) "（SPS 解析失败，用占位）" else "（来自 SPS）"))
        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        decoder.configure(format, surface, null, 0)
        decoder.start()
        Logx.i(TAG, "视频解码器=" + decoder.name + "（硬件解码器名称通常含 .qcom/.mtk/.exynos/.hw）")
        return decoder
    }

    private var fpsWindowStart = 0L
    private var fpsCount = 0

    private fun tickFps() {
        val now = System.currentTimeMillis()
        if (fpsWindowStart == 0L) { fpsWindowStart = now; fpsCount = 0; return }
        fpsCount++
        if (now - fpsWindowStart >= 3000) {
            val fps = fpsCount * 1000f / (now - fpsWindowStart)
            Logx.i(TAG, "渲染帧率≈" + String.format(java.util.Locale.US, "%.1f", fps) + " fps（3 秒窗口）")
            fpsWindowStart = now
            fpsCount = 0
        }
    }

    private fun queueAndDrain(decoder: MediaCodec, accessUnit: ByteArray, ptsUs: Long, info: MediaCodec.BufferInfo) {
        var inputIndex = -1
        var guard = 0
        while (!stopped && guard++ < 200) {
            inputIndex = decoder.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) break
        }
        if (stopped) return
        if (inputIndex < 0) return
        val input = decoder.getInputBuffer(inputIndex) ?: return
        if (input.capacity() < accessUnit.size) {
            Logx.w(TAG, "帧过大，丢弃 " + accessUnit.size)
            return
        }
        input.clear()
        input.put(accessUnit)
        decoder.queueInputBuffer(inputIndex, 0, accessUnit.size, ptsUs, 0)

        var outGuard = 0
        while (!stopped && outGuard++ < 64) {
            val outputIndex = decoder.dequeueOutputBuffer(info, 0)
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val fmt = decoder.outputFormat
                outWidth = if (fmt.containsKey(MediaFormat.KEY_WIDTH)) fmt.getInteger(MediaFormat.KEY_WIDTH) else 0
                outHeight = if (fmt.containsKey(MediaFormat.KEY_HEIGHT)) fmt.getInteger(MediaFormat.KEY_HEIGHT) else 0
                Logx.i(TAG, "解码器输出格式 " + outWidth + "x" + outHeight)
                continue
            }
            if (outputIndex >= 0) {
                decoder.releaseOutputBuffer(outputIndex, true)
                tickFps()
                // 首帧回调必须在"已送显"之后：否则角标会先亮、画面还是黑屏（评审 N3）
                if (!firstFrameSent && (outWidth > 0 || info.size > 0)) {
                    firstFrameSent = true
                    Logx.i(TAG, "首帧已上屏")
                    listener.onFirstFrame(outWidth, outHeight)
                }
            }
        }
    }

    // ---------------- FLV 解析辅助 ----------------

    private fun readFlvHeader(input: InputStream) {
        val header = ByteArray(9)
        if (!readFully(input, header, 0, header.size)) throw IOException("FLV 头读取失败")
        if (header[0] != 'F'.code.toByte() || header[1] != 'L'.code.toByte() || header[2] != 'V'.code.toByte()) {
            throw IOException("不是 FLV 流")
        }
        val dataOffset = ((header[5].toInt() and 0xFF) shl 24) or ((header[6].toInt() and 0xFF) shl 16) or
            ((header[7].toInt() and 0xFF) shl 8) or (header[8].toInt() and 0xFF)
        if (dataOffset < 9) throw IOException("FLV dataOffset 非法")
        skipFully(input, (dataOffset - 9).toLong())
        skipFully(input, 4) // PreviousTagSize0
    }

    /** 返回 (lengthSize, Annex-B 的 VPS/SPS/PPS)。 */
    private fun parseHevcConfiguration(payload: ByteArray, offset: Int): Pair<Int, ByteArray>? {
        if (payload.size - offset < 23) return null
        val lengthSize = (payload[offset + 21].toInt() and 0x03) + 1
        val arrayCount = payload[offset + 22].toInt() and 0xFF
        var pos = offset + 23
        val csd = ByteArrayOutputStream()
        for (i in 0 until arrayCount) {
            if (pos + 3 > payload.size) return null
            val nalType = payload[pos].toInt() and 0x3F
            pos++
            val nalCount = readUInt16(payload, pos)
            pos += 2
            for (n in 0 until nalCount) {
                if (pos + 2 > payload.size) return null
                val nalLength = readUInt16(payload, pos)
                pos += 2
                if (nalLength <= 0 || pos + nalLength > payload.size) return null
                if (nalType == 32 || nalType == 33 || nalType == 34) {
                    csd.write(0); csd.write(0); csd.write(0); csd.write(1)
                    csd.write(payload, pos, nalLength)
                }
                pos += nalLength
            }
        }
        val bytes = csd.toByteArray()
        return if (bytes.isEmpty()) null else lengthSize to bytes
    }

    private fun toAnnexB(source: ByteArray, offset: Int, length: Int, lengthSize: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        if (hasStartCode(source, offset, length)) return source.copyOfRange(offset, offset + length)
        val out = ByteArrayOutputStream(length + 32)
        var pos = offset
        val end = offset + length
        while (pos + lengthSize <= end) {
            var nalLength = 0
            for (i in 0 until lengthSize) nalLength = (nalLength shl 8) or (source[pos + i].toInt() and 0xFF)
            pos += lengthSize
            if (nalLength <= 0 || pos + nalLength > end) break
            out.write(0); out.write(0); out.write(0); out.write(1)
            out.write(source, pos, nalLength)
            pos += nalLength
        }
        return out.toByteArray()
    }

    private fun hasStartCode(data: ByteArray, offset: Int, length: Int): Boolean =
        length >= 4 && data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() &&
            (data[offset + 2] == 1.toByte() || (data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte()))

    private fun readUInt16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun readUInt24(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 16) or ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset + 2].toInt() and 0xFF)

    private fun readSigned24(data: ByteArray, offset: Int): Int {
        var v = readUInt24(data, offset)
        if (v and 0x800000 != 0) v -= 0x1000000
        return v
    }

    private fun readFully(input: InputStream, target: ByteArray, offset: Int, length: Int): Boolean {
        var read = 0
        while (read < length) {
            val n = try { input.read(target, offset + read, length - read) } catch (t: Throwable) { -1 }
            if (n <= 0) return false
            read += n
        }
        return true
    }

    private fun skipFully(input: InputStream, length: Long) {
        var remaining = length
        val buf = ByteArray(4096)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n <= 0) throw IOException("skip 失败")
            remaining -= n
        }
    }

    companion object {
        private const val TAG = "LiveFlv"
        private const val VIDEO_TAG = 9
        private const val AUDIO_TAG = 8
        private const val CODEC_HEVC = 12
        private const val SOUND_AAC = 10
    }
}
