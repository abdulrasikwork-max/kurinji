package com.safesphere.app.feature.social.data

import java.io.File
import java.io.RandomAccessFile

/**
 * Phase 17C: the last step of the phone's re-pack. MediaMuxer itself never writes a position unless asked
 * (VideoPreparer never asks), but it does add a "meta" box with the Android version (some builds also the maker and
 * model). This turns every udta, meta and uuid box at the top level and inside moov, trak and mdia into a "free" box of
 * zeros, in place: the file keeps its length, so every chunk offset stays valid. The server does the same again with
 * stricter checks (backend Mp4Sanitizer). Pure apart from file I/O (tested on the JVM with built files).
 */
object Mp4Scrubber {

    /** The box types that hold metadata and are never needed to play a video. */
    val STRIPPED = setOf("udta", "meta", "uuid")
    private val CONTAINERS = setOf("moov", "trak", "mdia")
    private const val MAX_MOOV_BYTES = 8 * 1024 * 1024

    /** Thrown when the file is not laid out as a plain MP4 (the video is then not shared). */
    class NotMp4 : Exception("not a plain MP4")

    /** Neutralises the metadata boxes of [file] in place; returns how many were neutralised. */
    fun scrub(file: File): Int {
        RandomAccessFile(file, "rw").use { raf ->
            val length = raf.length()
            var neutralised = 0
            var pos = 0L
            while (pos < length) {
                val box = header(raf, pos, length)
                when {
                    box.type in STRIPPED -> {
                        zeroInFile(raf, box)
                        neutralised++
                    }
                    box.type == "moov" -> {
                        if (box.size > MAX_MOOV_BYTES) throw NotMp4()
                        val bytes = ByteArray(box.size.toInt())
                        raf.seek(pos)
                        raf.readFully(bytes)
                        neutralised += scrubChildren(bytes, box.header, bytes.size, 0)
                        raf.seek(pos)
                        raf.write(bytes)
                    }
                }
                pos += box.size
            }
            raf.fd.sync()
            return neutralised
        }
    }

    /** True if a metadata box is still present where [scrub] looks (a check after scrubbing). */
    fun hasMetadata(file: File): Boolean {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            var pos = 0L
            while (pos < length) {
                val box = header(raf, pos, length)
                if (box.type in STRIPPED) return true
                if (box.type == "moov") {
                    if (box.size > MAX_MOOV_BYTES) throw NotMp4()
                    val bytes = ByteArray(box.size.toInt())
                    raf.seek(pos)
                    raf.readFully(bytes)
                    if (findIn(bytes, box.header, bytes.size, 0)) return true
                }
                pos += box.size
            }
            return false
        }
    }

    private class Box(val type: String, val start: Long, val header: Int, val size: Long)

    private fun header(raf: RandomAccessFile, pos: Long, length: Long): Box {
        if (length - pos < 8) throw NotMp4()
        raf.seek(pos)
        var size = raf.readInt().toLong() and 0xFFFFFFFFL
        val type = fourCc(ByteArray(4).also { raf.readFully(it) }, 0)
        var header = 8
        if (size == 1L) {
            if (length - pos < 16) throw NotMp4()
            size = raf.readLong()
            header = 16
        } else if (size == 0L) {
            size = length - pos
        }
        if (size < header || size > length - pos) throw NotMp4()
        return Box(type, pos, header, size)
    }

    private fun scrubChildren(b: ByteArray, from: Int, to: Int, depth: Int): Int {
        if (depth > 8) throw NotMp4()
        var count = 0
        var pos = from
        while (pos < to) {
            val (type, header, end) = child(b, pos, to)
            if (type in STRIPPED) {
                "free".toByteArray(Charsets.ISO_8859_1).copyInto(b, pos + 4)
                b.fill(0, pos + header, end)
                count++
            } else if (type in CONTAINERS) {
                count += scrubChildren(b, pos + header, end, depth + 1)
            }
            pos = end
        }
        return count
    }

    private fun findIn(b: ByteArray, from: Int, to: Int, depth: Int): Boolean {
        if (depth > 8) throw NotMp4()
        var pos = from
        while (pos < to) {
            val (type, header, end) = child(b, pos, to)
            if (type in STRIPPED) return true
            if (type in CONTAINERS && findIn(b, pos + header, end, depth + 1)) return true
            pos = end
        }
        return false
    }

    private fun child(b: ByteArray, pos: Int, to: Int): Triple<String, Int, Int> {
        if (to - pos < 8) throw NotMp4()
        var size = u32(b, pos)
        var header = 8
        if (size == 1L) {
            if (to - pos < 16) throw NotMp4()
            size = (u32(b, pos + 8) shl 32) or u32(b, pos + 12)
            header = 16
        }
        if (size < header || size > to - pos) throw NotMp4()
        return Triple(fourCc(b, pos + 4), header, (pos + size).toInt())
    }

    private fun zeroInFile(raf: RandomAccessFile, box: Box) {
        raf.seek(box.start + 4)
        raf.write("free".toByteArray(Charsets.ISO_8859_1))
        raf.seek(box.start + box.header)
        val zeros = ByteArray(64 * 1024)
        var left = box.size - box.header
        while (left > 0) {
            val n = minOf(left, zeros.size.toLong()).toInt()
            raf.write(zeros, 0, n)
            left -= n
        }
    }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun fourCc(b: ByteArray, at: Int) = String(b, at, 4, Charsets.ISO_8859_1)
}
