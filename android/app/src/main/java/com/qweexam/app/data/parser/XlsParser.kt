package com.qweexam.app.data.parser

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 老版 Excel(.xls, BIFF8) 解析器 —— 纯 Kotlin/JVM 实现。
 * 移植自原型 prototype/xls_reader.py（已用真实 题库2.xls 验证）。
 *
 * 覆盖：OLE2 容器（DIFAT/FAT/目录/Workbook 流）、
 * SST 共享字符串表（含 CONTINUE 拆分、富文本 run 数组）、
 * 单元格（LABELSST/LABEL/NUMBER/RK/MULRK/FORMULA+STRING）。
 */
object XlsParser {

    private const val FREE = 0xFFFFFFFFL
    private const val END = 0xFFFFFFFEL
    private const val FAT_SECT = 0xFFFFFFFDL
    private const val DIF_SECT = 0xFFFFFFFCL

    private const val REC_BOF = 0x0809
    private const val REC_EOF = 0x000A
    private const val REC_SST = 0x00FC
    private const val REC_CONTINUE = 0x003C
    private const val REC_LABELSST = 0x00FD
    private const val REC_LABEL = 0x0204
    private const val REC_NUMBER = 0x0203
    private const val REC_RK = 0x027E
    private const val REC_MULRK = 0x00BD
    private const val REC_FORMULA = 0x0006
    private const val REC_STRING = 0x0207
    private const val DT_WORKSHEET = 0x0010

    // ---------- 底层字节工具 ----------

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun leDouble(b: ByteArray, off: Int): Double =
        ByteBuffer.wrap(b, off, 8).order(ByteOrder.LITTLE_ENDIAN).double

    private fun fmtNum(d: Double): String =
        if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

    private fun decodeRk(rk: Long): Double {
        val v: Double
        if (rk and 0x02L != 0L) {
            var vi = rk shr 2
            if (vi and 0x20000000L != 0L) vi -= 0x40000000L
            v = vi.toDouble()
        } else {
            val high = (rk and 0xFFFFFFFCL).toInt()
            val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0).putInt(high).array()
            v = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).double
        }
        return if (rk and 0x01L != 0L) v / 100.0 else v
    }

    private fun plainString(payload: ByteArray, off: Int): String {
        val cch = u16(payload, off)
        val fhigh = payload[off + 2].toInt() and 0x01 != 0
        return if (fhigh) {
            String(payload, off + 3, cch * 2, Charsets.UTF_16LE)
        } else {
            buildString {
                for (i in 0 until cch) append((payload[off + 3 + i].toInt() and 0xFF).toChar())
            }
        }
    }

    // ---------- OLE2 容器 ----------

    private fun readChain(data: ByteArray, sectorSize: Int, fat: List<Long>, start: Long, size: Int?): ByteArray {
        val out = ByteArrayOutputStream()
        var sid = start
        val visited = HashSet<Long>()
        var steps = 0
        while (sid != END && !visited.contains(sid) && steps < 10_000_000) {
            if (sid < 0 || sid >= fat.size || sid == FREE) break
            visited.add(sid)
            val off = ((sid + 1) * sectorSize).toInt()
            val end = minOf(off + sectorSize, data.size)
            out.write(data, off, end - off)
            sid = fat[sid.toInt()]
            steps++
            if (size != null && out.size() >= size) break
        }
        val bytes = out.toByteArray()
        return if (size != null) bytes.copyOf(minOf(size, bytes.size)) else bytes
    }

    private fun workbookStream(data: ByteArray): ByteArray {
        val sectorShift = u16(data, 0x1E)
        val sectorSize = 1 shl sectorShift
        val numFat = u32(data, 0x2C)
        val firstDir = u32(data, 0x30)
        val firstDifat = u32(data, 0x44)
        val numDifat = u32(data, 0x48)

        // DIFAT：头部 109 项 + DIFAT 扇区链
        val difat = mutableListOf<Long>()
        for (i in 0 until 109) difat.add(u32(data, 0x4C + i * 4))
        var sid = firstDifat
        val per = sectorSize / 4
        var guard = 0
        while (sid != FREE && sid != END && guard < 1000 && numDifat > 0) {
            guard++
            val off = ((sid + 1) * sectorSize).toInt()
            for (i in 0 until per - 1) {
                val v = u32(data, off + i * 4)
                if (v != FREE && v != END && v != FAT_SECT && v != DIF_SECT) difat.add(v)
            }
            sid = u32(data, off + (per - 1) * 4)
        }

        // FAT
        val fat = mutableListOf<Long>()
        var count = 0L
        for (fatSector in difat) {
            if (count >= numFat) break
            count++
            val off = ((fatSector + 1) * sectorSize).toInt()
            for (i in 0 until per) fat.add(u32(data, off + i * 4))
        }

        // 目录 → 找 Workbook 流
        val dirBytes = readChain(data, sectorSize, fat, firstDir, null)
        var start = -1L
        var size = 0L
        var i = 0
        while (i + 128 <= dirBytes.size) {
            val nameLen = u16(dirBytes, i + 0x40)
            val objType = dirBytes[i + 0x42].toInt()
            val st = u32(dirBytes, i + 0x74)
            val sz = u32(dirBytes, i + 0x78) // 低 32 位，<4GB 足够
            if (nameLen > 0 && objType == 2) {
                val name = String(dirBytes, i, nameLen - 2, Charsets.UTF_16LE)
                if (name == "Workbook" || name == "Book") {
                    start = st; size = sz
                    break
                }
            }
            i += 128
        }
        require(start >= 0) { "未在 .xls 中找到 Workbook 流" }
        return readChain(data, sectorSize, fat, start, size.toInt())
    }

    // ---------- BIFF 记录 ----------

    private fun parseRecords(stream: ByteArray): List<Pair<Int, ByteArray>> {
        val records = mutableListOf<Pair<Int, ByteArray>>()
        var pos = 0
        while (pos + 4 <= stream.size) {
            val rid = u16(stream, pos)
            val rlen = u16(stream, pos + 2)
            val payload = stream.copyOfRange(pos + 4, pos + 4 + rlen)
            records.add(rid to payload)
            pos += 4 + rlen
        }
        return records
    }

    /** 跨记录（含 CONTINUE）读取的字节游标 */
    private class StrStream(private val chunks: List<ByteArray>) {
        var ci = 0
            private set
        private var off = 0

        fun read(n: Int): ByteArray {
            val out = ByteArray(n)
            var got = 0
            while (got < n) {
                if (ci >= chunks.size) throw IndexOutOfBoundsException("字符串读取越界")
                val avail = chunks[ci].size - off
                if (avail == 0) {
                    ci++; off = 0
                    continue
                }
                val take = minOf(avail, n - got)
                System.arraycopy(chunks[ci], off, out, got, take)
                off += take; got += take
            }
            return out
        }

        fun skip(n: Int) { read(n) }
        fun u8(): Int = read(1)[0].toInt() and 0xFF
        fun u16(): Int { val b = read(2); return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) }
        fun u32(): Long { val b = read(4); return u32Of(b, 0) }

        /** 读取 cch 个字符（可能跨 CONTINUE；plain 字符串续段带新 flags 字节） */
        fun readChars(cch: Int, fhighIn: Boolean, plainCont: Boolean): String {
            var fhigh = fhighIn
            val sb = StringBuilder(cch)
            var remaining = cch
            while (remaining > 0) {
                val avail = chunks[ci].size - off
                if (avail == 0) {
                    if (ci + 1 < chunks.size) {
                        ci++; off = 0
                        if (plainCont) fhigh = (u8() and 0x01) != 0
                    } else {
                        break
                    }
                    continue
                }
                if (fhigh) {
                    val take = minOf(avail, remaining * 2)
                    val raw = read(take)
                    var k = 0
                    while (k + 1 < raw.size) {
                        sb.append(((raw[k].toInt() and 0xFF) or ((raw[k + 1].toInt() and 0xFF) shl 8)).toChar())
                        k += 2
                    }
                    remaining -= raw.size / 2
                } else {
                    val take = minOf(avail, remaining)
                    val raw = read(take)
                    for (b in raw) sb.append((b.toInt() and 0xFF).toChar())
                    remaining -= take
                }
            }
            return sb.toString()
        }
    }

    private fun u32Of(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun parseSst(records: List<Pair<Int, ByteArray>>, idx: Int): List<String> {
        val chunks = mutableListOf(records[idx].second)
        var j = idx + 1
        while (j < records.size && records[j].first == REC_CONTINUE) {
            chunks.add(records[j].second); j++
        }
        if (chunks[0].size < 8) return emptyList()
        val unique = u32(chunks[0], 4).toInt()
        val s = StrStream(chunks)
        s.skip(8)
        val strings = mutableListOf<String>()
        for (n in 0 until unique) {
            val cch = s.u16()
            val flags = s.u8()
            val fhigh = flags and 0x01 != 0
            val frich = flags and 0x08 != 0
            val fext = flags and 0x04 != 0
            var cRun = 0
            var cbExt = 0
            if (frich) cRun = s.u16()
            if (fext) cbExt = s.u32().toInt()
            val plain = !frich && !fext
            strings.add(s.readChars(cch, fhigh, plain))
            // 富文本 run 数组与扩展数据在字符之后
            if (frich && cRun > 0) s.skip(cRun * 4)
            if (fext && cbExt > 0) s.skip(cbExt)
        }
        return strings
    }

    /** .xls bytes -> [工作表][行][列]（含表头行） */
    fun parse(data: ByteArray): List<List<List<String>>> {
        val stream = workbookStream(data)
        val records = parseRecords(stream)

        // SST
        var sst = emptyList<String>()
        for (i in records.indices) {
            if (records[i].first == REC_SST) {
                sst = parseSst(records, i)
                break
            }
        }

        // 工作表子流单元格
        val sheets = mutableListOf<Map<Pair<Int, Int>, String>>()
        var cur = mutableMapOf<Pair<Int, Int>, String>()
        var inSheet = false
        var pendingString: Pair<Int, Int>? = null
        for ((rid, payload) in records) {
            when {
                rid == REC_BOF -> {
                    val dt = if (payload.size >= 4) u16(payload, 2) else 0
                    if (dt == DT_WORKSHEET) { cur = mutableMapOf(); inSheet = true }
                    else if (dt == 0x0005) inSheet = false
                }
                rid == REC_EOF -> {
                    if (inSheet) { sheets.add(cur); cur = mutableMapOf(); inSheet = false }
                }
                !inSheet -> {}
                payload.size < 6 -> {}
                else -> {
                    val row = u16(payload, 0)
                    val col = u16(payload, 2)
                    when (rid) {
                        REC_LABELSST -> {
                            val isst = u32(payload, 6)
                            if (isst < sst.size) cur[row to col] = sst[isst.toInt()]
                        }
                        REC_LABEL -> cur[row to col] = plainString(payload, 6)
                        REC_NUMBER -> cur[row to col] = fmtNum(leDouble(payload, 6))
                        REC_RK -> cur[row to col] = fmtNum(decodeRk(u32(payload, 6)))
                        REC_MULRK -> {
                            var k = 4
                            while (k + 6 <= payload.size) {
                                val c = u16(payload, k)
                                val rk = u32(payload, k + 2)
                                cur[row to c] = fmtNum(decodeRk(rk))
                                k += 6
                            }
                        }
                        REC_FORMULA -> {
                            val cached = payload.copyOfRange(6, minOf(14, payload.size))
                            if (cached.size >= 8 && cached[6].toInt() == 0xFF && cached[7].toInt() == 0xFF) {
                                when (cached[0].toInt()) {
                                    0 -> cur[row to col] = if (cached[1] != 0.toByte()) "1" else "0"
                                    2 -> pendingString = row to col
                                    else -> cur[row to col] = ""
                                }
                            } else if (cached.size >= 8) {
                                cur[row to col] = fmtNum(ByteBuffer.wrap(cached).order(ByteOrder.LITTLE_ENDIAN).double)
                            }
                        }
                        REC_STRING -> if (pendingString != null) {
                            cur[pendingString!!] = plainString(payload, 0)
                            pendingString = null
                        }
                    }
                }
            }
        }

        // 转二维数组
        val out = mutableListOf<List<List<String>>>()
        for (grid in sheets) {
            if (grid.isEmpty()) continue
            val maxR = grid.keys.maxOf { it.first }
            val maxC = grid.keys.maxOf { it.second }
            val rows = (0..maxR).map { r ->
                (0..maxC).map { c -> grid[r to c] ?: "" }
            }.toMutableList()
            while (rows.isNotEmpty() && rows.last().all { it.isBlank() }) rows.removeAt(rows.size - 1)
            if (rows.isNotEmpty()) out.add(rows)
        }
        return out
    }
}
