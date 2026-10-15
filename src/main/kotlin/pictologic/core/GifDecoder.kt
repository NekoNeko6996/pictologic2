package pictologic.core

import arc.graphics.Pixmap
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.ArrayList

/**
 * Lightweight, pure-Java GIF decoder for Mindustry.
 * Decodes animated GIF files directly into lists of Pixmap frames and frame delay times.
 */
class GifDecoder {

    class GifFrame(val pixmap: Pixmap, val delayMs: Int)

    val frames = ArrayList<GifFrame>()

    private var inStream: InputStream? = null
    private var status = STATUS_OK

    var width = 0
    var height = 0
    private var gctFlag = false
    private var gctSize = 0
    private var bgIndex = 0
    private var bgColor = 0
    private var pixelAspect = 0

    private var gct: IntArray? = null
    private var lct: IntArray? = null
    private var act: IntArray? = null

    private var interlace = false
    private var lctFlag = false
    private var lctSize = 0

    private var ix = 0
    private var iy = 0
    private var iw = 0
    private var ih = 0

    private var lrx = 0
    private var lry = 0
    private var lrw = 0
    private var lrh = 0

    // Block buffer
    private val block = ByteArray(256)
    private var blockSize = 0

    // LZW decoder state
    private val prefix = ShortArray(MAX_STACK_SIZE)
    private val suffix = ByteArray(MAX_STACK_SIZE)
    private val pixelStack = ByteArray(MAX_STACK_SIZE + 1)

    // Graphics Control Extension
    private var dispose = 0
    private var lastDispose = 0
    private var transparency = false
    private var delay = 0
    private var transIndex = 0

    private var pixels: ByteArray? = null
    private var imagePixels: IntArray? = null
    private var lastImagePixels: IntArray? = null

    fun read(inputStream: InputStream): Int {
        init()
        inStream = BufferedInputStream(inputStream)
        readHeader()
        if (!err()) {
            readContents()
            if (frames.isEmpty()) {
                status = STATUS_FORMAT_ERROR
            }
        }
        try {
            inStream?.close()
        } catch (_: Exception) {}
        return status
    }

    private fun init() {
        status = STATUS_OK
        frames.clear()
        gct = null
        lct = null
        imagePixels = null
        lastImagePixels = null
        dispose = 0
        lastDispose = 0
    }

    private fun err(): Boolean = status != STATUS_OK

    private fun readByte(): Int {
        return try {
            inStream!!.read()
        } catch (_: Exception) {
            status = STATUS_OPEN_ERROR
            0
        }
    }

    private fun readShort(): Int {
        return readByte() or (readByte() shl 8)
    }

    private fun readBlock(): Int {
        blockSize = readByte()
        var n = 0
        if (blockSize > 0) {
            try {
                var count: Int
                while (n < blockSize) {
                    count = inStream!!.read(block, n, blockSize - n)
                    if (count == -1) break
                    n += count
                }
            } catch (_: Exception) {}
            if (n < blockSize) status = STATUS_OPEN_ERROR
        }
        return n
    }

    private fun readHeader() {
        val id = StringBuilder()
        for (i in 0 until 6) id.append(readByte().toChar())
        if (!id.toString().startsWith("GIF")) {
            status = STATUS_FORMAT_ERROR
            return
        }
        readLSD()
        if (gctFlag && !err()) {
            gct = readColorTable(gctSize)
            bgColor = gct?.getOrNull(bgIndex) ?: 0
        }
    }

    private fun readLSD() {
        width = readShort()
        height = readShort()
        val packed = readByte()
        gctFlag = (packed and 0x80) != 0
        gctSize = 2 shl (packed and 7)
        bgIndex = readByte()
        pixelAspect = readByte()
    }

    private fun readColorTable(ncolors: Int): IntArray {
        val nbytes = 3 * ncolors
        val c = ByteArray(nbytes)
        var n = 0
        try {
            var count: Int
            while (n < nbytes) {
                count = inStream!!.read(c, n, nbytes - n)
                if (count == -1) break
                n += count
            }
        } catch (_: Exception) {}
        if (n < nbytes) {
            status = STATUS_OPEN_ERROR
            return IntArray(ncolors)
        }
        val tab = IntArray(ncolors)
        var i = 0
        var j = 0
        while (i < ncolors) {
            val r = c[j++].toInt() and 0xFF
            val g = c[j++].toInt() and 0xFF
            val b = c[j++].toInt() and 0xFF
            tab[i++] = (r shl 24) or (g shl 16) or (b shl 8) or 0xFF
        }
        return tab
    }

    private fun readContents() {
        var done = false
        while (!done && !err()) {
            when (readByte()) {
                0x2C -> readImage()
                0x21 -> {
                    when (readByte()) {
                        0xF9 -> readGraphicControlExt()
                        0xFF -> {
                            readBlock()
                            skip()
                        }
                        else -> skip()
                    }
                }
                0x3B -> done = true
                0x00 -> {}
                else -> status = STATUS_FORMAT_ERROR
            }
        }
    }

    private fun readGraphicControlExt() {
        readByte() // Block size
        val packed = readByte()
        dispose = (packed and 0x1C) shr 2
        if (dispose == 0) dispose = 1
        transparency = (packed and 1) != 0
        delay = readShort() * 10
        if (delay <= 0) delay = 100
        transIndex = readByte()
        readByte() // Block terminator
    }

    private fun readImage() {
        ix = readShort()
        iy = readShort()
        iw = readShort()
        ih = readShort()
        val packed = readByte()
        lctFlag = (packed and 0x80) != 0
        interlace = (packed and 0x40) != 0
        lctSize = 2 shl (packed and 7)
        act = if (lctFlag) {
            lct = readColorTable(lctSize)
            lct
        } else {
            gct
        }

        var save = 0
        if (transparency && act != null && transIndex in act!!.indices) {
            save = act!![transIndex]
            act!![transIndex] = 0
        }

        decodeBitmapData()
        skip()

        if (err()) return

        setPixels()

        val pixmap = Pixmap(width, height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                pixmap.set(x, y, imagePixels!![row + x])
            }
        }
        frames.add(GifFrame(pixmap, delay))

        if (transparency && act != null && transIndex in act!!.indices) {
            act!![transIndex] = save
        }
        resetFrame()
    }

    private fun decodeBitmapData() {
        val nullCode = -1
        val npix = iw * ih
        if (pixels == null || pixels!!.size < npix) {
            pixels = ByteArray(npix)
        }
        val dataSize = readByte()
        val clear = 1 shl dataSize
        val endOfInformation = clear + 1
        var available = clear + 2
        var oldCode = nullCode
        var codeSize = dataSize + 1
        var codeMask = (1 shl codeSize) - 1

        for (code in 0 until clear) {
            prefix[code] = 0
            suffix[code] = code.toByte()
        }

        var datum = 0
        var bits = 0
        var count = 0
        var bi = 0
        var pi = 0
        var top = 0
        var first = 0

        while (pi < npix) {
            if (top == 0) {
                if (bits < codeSize) {
                    if (count == 0) {
                        count = readBlock()
                        if (count <= 0) break
                        bi = 0
                    }
                    datum = datum or ((block[bi].toInt() and 0xFF) shl bits)
                    bits += 8
                    bi++
                    count--
                    continue
                }

                var code = datum and codeMask
                datum = datum shr codeSize
                bits -= codeSize

                if (code > available || code == endOfInformation) break
                if (code == clear) {
                    codeSize = dataSize + 1
                    codeMask = (1 shl codeSize) - 1
                    available = clear + 2
                    oldCode = nullCode
                    continue
                }

                if (oldCode == nullCode) {
                    pixelStack[top++] = suffix[code]
                    oldCode = code
                    first = code
                    continue
                }

                val inCode = code
                if (code == available) {
                    pixelStack[top++] = first.toByte()
                    code = oldCode
                }

                while (code > clear) {
                    pixelStack[top++] = suffix[code]
                    code = prefix[code].toInt()
                }

                first = suffix[code].toInt() and 0xFF
                pixelStack[top++] = first.toByte()

                if (available < MAX_STACK_SIZE) {
                    prefix[available] = oldCode.toShort()
                    suffix[available] = first.toByte()
                    available++
                    if ((available and codeMask) == 0 && available < MAX_STACK_SIZE) {
                        codeSize++
                        codeMask += available
                    }
                }
                oldCode = inCode
            }

            top--
            pixels!![pi++] = pixelStack[top]
        }

        for (i in pi until npix) {
            pixels!![i] = 0
        }
    }

    private fun setPixels() {
        if (imagePixels == null) {
            imagePixels = IntArray(width * height)
        }

        // 1. Apply previous frame's disposal method BEFORE drawing the current frame
        if (lastDispose > 0) {
            if (lastDispose == 3) {
                // Restore to previous canvas state
                if (lastImagePixels != null) {
                    System.arraycopy(lastImagePixels!!, 0, imagePixels!!, 0, width * height)
                }
            } else if (lastDispose == 2) {
                // Restore previous frame's region to background/transparent
                val fillColor = if (transparency) 0 else bgColor
                for (y in lry until lry + lrh) {
                    if (y !in 0 until height) continue
                    val rowOffset = y * width
                    for (x in lrx until lrx + lrw) {
                        if (x in 0 until width) {
                            imagePixels!![rowOffset + x] = fillColor
                        }
                    }
                }
            }
        }

        // 2. If current frame requests dispose == 3 (Restore to previous),
        // snapshot the current canvas state BEFORE drawing this frame's pixels
        if (dispose == 3) {
            if (lastImagePixels == null) lastImagePixels = IntArray(width * height)
            System.arraycopy(imagePixels!!, 0, lastImagePixels!!, 0, width * height)
        }

        // 3. Draw current frame pixels onto canvas
        var pass = 1
        var inc = 8
        var iline = 0
        for (i in 0 until ih) {
            var line = i
            if (interlace) {
                if (iline >= ih) {
                    pass++
                    when (pass) {
                        2 -> { iline = 4; inc = 8 }
                        3 -> { iline = 2; inc = 4 }
                        4 -> { iline = 1; inc = 2 }
                    }
                }
                line = iline
                iline += inc
            }
            line += iy
            if (line < height) {
                val k = line * width
                var dx = k + ix
                var dlim = dx + iw
                if (k + width < dlim) dlim = k + width
                var sx = i * iw
                while (dx < dlim) {
                    val index = pixels!![sx++].toInt() and 0xFF
                    val c = act?.getOrNull(index) ?: 0
                    if (c != 0) {
                        imagePixels!![dx] = c
                    }
                    dx++
                }
            }
        }

        // 4. If current frame keeps its state (dispose == 1), update backup canvas
        if (dispose == 1) {
            if (lastImagePixels == null) lastImagePixels = IntArray(width * height)
            System.arraycopy(imagePixels!!, 0, lastImagePixels!!, 0, width * height)
        }
    }

    private fun resetFrame() {
        lastDispose = dispose
        lrx = ix; lry = iy; lrw = iw; lrh = ih
        ix = 0; iy = 0; iw = 0; ih = 0
        dispose = 0
        transparency = false
        delay = 0
        lct = null
    }

    private fun skip() {
        do {
            readBlock()
        } while (blockSize > 0 && !err())
    }

    companion object {
        const val STATUS_OK = 0
        const val STATUS_FORMAT_ERROR = 1
        const val STATUS_OPEN_ERROR = 2
        private const val MAX_STACK_SIZE = 4096
    }
}