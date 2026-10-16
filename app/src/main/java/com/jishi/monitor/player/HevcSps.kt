package com.jishi.monitor.player

/**
 * 从 hvcC（HEVCDecoderConfigurationRecord）里的 SPS 提取分辨率。
 *
 * 为什么需要：EasyNVR 的 FLV onMetaData 只带 audiocodecid/videocodecid，没有 width/height；
 * 而部分解码器（雷电模拟器的 OMX.qcom.video.decoder.hevc）在 Format 缺少宽高时直接抛
 * IllegalArgumentException: Invalid size(s), width=-1, height=-1（本机实测）。
 */
object HevcSps {

    /** 直播路径用：csd 是 Annex-B，直接传单个 SPS NAL（含 2 字节 NAL 头）。 */
    fun dimensionsFromAnnexBNal(spsNal: ByteArray): Pair<Int, Int>? = parseSps(spsNal)

    /** 返回 (width, height)，解析失败返回 null。 */
    fun dimensionsFromHvcC(hvcc: ByteArray): Pair<Int, Int>? {
        val sps = findSps(hvcc) ?: return null
        return parseSps(sps)
    }

    /** hvcC 结构：22 字节定长头 + numOfArrays + 各数组（含 NAL 类型 33 = SPS）。 */
    private fun findSps(c: ByteArray): ByteArray? {
        if (c.size < 23) return null
        var off = 22
        val numArrays = c[off].toInt() and 0xFF
        off++
        for (i in 0 until numArrays) {
            if (off + 3 > c.size) return null
            val nalType = c[off].toInt() and 0x3F
            val numNalus = ((c[off + 1].toInt() and 0xFF) shl 8) or (c[off + 2].toInt() and 0xFF)
            off += 3
            for (n in 0 until numNalus) {
                if (off + 2 > c.size) return null
                val len = ((c[off].toInt() and 0xFF) shl 8) or (c[off + 1].toInt() and 0xFF)
                off += 2
                if (len <= 0 || off + len > c.size) return null
                if (nalType == 33) {
                    return c.copyOfRange(off, off + len)
                }
                off += len
            }
        }
        return null
    }

    /** SPS RBSP：跳过 NAL 头与 profile_tier_level，读到 pic_width/height_in_luma_samples。 */
    private fun parseSps(nal: ByteArray): Pair<Int, Int>? {
        if (nal.size < 6) return null
        val rbsp = removeEmulationPrevention(nal, 2)
        val r = BitReader(rbsp)
        return try {
            r.u(4) // sps_video_parameter_set_id
            val maxSubLayersMinus1 = r.u(3)
            r.u(1) // sps_temporal_id_nesting_flag
            profileTierLevel(r, maxSubLayersMinus1)
            r.ue() // sps_seq_parameter_set_id
            val chromaFormatIdc = r.ue()
            if (chromaFormatIdc == 3) r.u(1)
            val width = r.ue()
            val height = r.ue()
            if (width in 16..16384 && height in 16..16384) width to height else null
        } catch (t: Throwable) {
            null
        }
    }

    private fun profileTierLevel(r: BitReader, maxSubLayersMinus1: Int) {
        r.u(2); r.u(1); r.u(5)      // general_profile_space / tier_flag / profile_idc
        r.skip(32)                   // general_profile_compatibility_flag[32]
        r.u(1); r.u(1); r.u(1); r.u(1) // progressive / interlaced / non_packed / frame_only
        r.skip(44)                   // general_reserved_zero_44bits（H.265 规范为 44 位）
        r.u(8)                       // general_level_idc
        val subProfile = BooleanArray(maxSubLayersMinus1)
        val subLevel = BooleanArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) {
            subProfile[i] = r.u(1) == 1
            subLevel[i] = r.u(1) == 1
        }
        if (maxSubLayersMinus1 > 0) {
            for (i in maxSubLayersMinus1 until 8) r.skip(2)
        }
        for (i in 0 until maxSubLayersMinus1) {
            if (subProfile[i]) r.skip(88)
            if (subLevel[i]) r.u(8)
        }
    }

    private fun removeEmulationPrevention(data: ByteArray, from: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size)
        var zeros = 0
        for (i in from until data.size) {
            val b = data[i].toInt() and 0xFF
            if (zeros >= 2 && b == 0x03) {
                zeros = 0
                continue
            }
            out.write(b)
            zeros = if (b == 0x00) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class BitReader(private val data: ByteArray) {
        private var bitPos = 0
        fun u(n: Int): Int {
            var v = 0
            repeat(n) {
                val byteIndex = bitPos ushr 3
                if (byteIndex >= data.size) throw IndexOutOfBoundsException("sps eof")
                val bit = (data[byteIndex].toInt() ushr (7 - (bitPos and 7))) and 1
                v = (v shl 1) or bit
                bitPos++
            }
            return v
        }
        fun skip(n: Int) { bitPos += n }
        /** ue(v)：指数哥伦布。 */
        fun ue(): Int {
            var leadingZeros = 0
            while (u(1) == 0) {
                leadingZeros++
                if (leadingZeros > 31) throw IllegalStateException("bad ue")
            }
            if (leadingZeros == 0) return 0
            return (1 shl leadingZeros) - 1 + u(leadingZeros)
        }
    }
}
