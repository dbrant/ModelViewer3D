package com.dmitrybrant.modelviewer.vdb

import com.dmitrybrant.modelviewer.util.Util
import java.io.IOException

/*
* Minimal decoder for Blosc (version 1) buffers, which is the default compression scheme used
* in OpenVDB files. OpenVDB always compresses with the LZ4 codec and byte shuffling, so those
* are the only options supported here.
*
* Info on the Blosc format: https://github.com/Blosc/c-blosc/blob/main/README_CHUNK_FORMAT.rst
* Info on the LZ4 block format: https://github.com/lz4/lz4/blob/dev/doc/lz4_Block_format.md
*
* Copyright 2026 Dmitry Brant. All rights reserved.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*   http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
class Blosc {
    private var shuffleBuffer = ByteArray(0)

    fun decompress(src: ByteArray, srcLength: Int, dest: ByteArray, destLength: Int) {
        if (srcLength < HEADER_SIZE) {
            throw IOException("Invalid Blosc buffer.")
        }
        val flags = src[2].toInt() and 0xff
        val typeSize = src[3].toInt() and 0xff
        val numBytes = Util.readIntLe(src, 4)
        val blockSize = Util.readIntLe(src, 8)
        if (numBytes != destLength) {
            throw IOException("Unexpected Blosc buffer size: $numBytes, expected $destLength.")
        }
        if (numBytes == 0) {
            return
        }
        if (flags and FLAG_MEMCPYED != 0) {
            if (HEADER_SIZE + numBytes > srcLength) {
                throw IOException("Truncated Blosc buffer.")
            }
            System.arraycopy(src, HEADER_SIZE, dest, 0, numBytes)
            return
        }
        if ((flags and 0xe0) shr 5 != COMPRESSOR_LZ4) {
            throw IOException("Unsupported Blosc compressor: ${(flags and 0xe0) shr 5}")
        }
        if (flags and FLAG_BITSHUFFLE != 0) {
            throw IOException("Blosc bit-shuffling is not supported.")
        }
        if (blockSize <= 0 || typeSize <= 0) {
            throw IOException("Invalid Blosc header.")
        }
        val doShuffle = (flags and FLAG_SHUFFLE != 0) && typeSize > 1
        val dontSplit = flags and FLAG_DONT_SPLIT != 0
        val leftover = numBytes % blockSize
        val numBlocks = numBytes / blockSize + (if (leftover > 0) 1 else 0)
        if (doShuffle && shuffleBuffer.size < blockSize) {
            shuffleBuffer = ByteArray(blockSize)
        }
        val temp = if (doShuffle) shuffleBuffer else dest

        for (block in 0 until numBlocks) {
            val isLeftover = leftover > 0 && block == numBlocks - 1
            val currentBlockSize = if (isLeftover) leftover else blockSize
            val destOffset = block * blockSize
            var srcPos = Util.readIntLe(src, HEADER_SIZE + block * 4)
            val numSplits = if (!dontSplit && typeSize <= MAX_SPLITS && !isLeftover
                && currentBlockSize / typeSize >= MIN_BUFFERSIZE) typeSize else 1
            val splitSize = currentBlockSize / numSplits
            var outPos = if (doShuffle) 0 else destOffset

            for (split in 0 until numSplits) {
                if (srcPos < 0 || srcPos + 4 > srcLength) {
                    throw IOException("Truncated Blosc buffer.")
                }
                val compressedSize = Util.readIntLe(src, srcPos)
                srcPos += 4
                if (compressedSize < 0 || srcPos + compressedSize > srcLength) {
                    throw IOException("Truncated Blosc buffer.")
                }
                if (compressedSize == splitSize) {
                    System.arraycopy(src, srcPos, temp, outPos, splitSize)
                } else if (lz4Decompress(src, srcPos, compressedSize, temp, outPos, splitSize) != splitSize) {
                    throw IOException("Corrupt LZ4 data in Blosc buffer.")
                }
                srcPos += compressedSize
                outPos += splitSize
            }
            if (doShuffle) {
                unshuffle(typeSize, currentBlockSize, temp, dest, destOffset)
            }
        }
    }

    private fun unshuffle(typeSize: Int, blockSize: Int, src: ByteArray, dest: ByteArray, destOffset: Int) {
        val numElements = blockSize / typeSize
        for (i in 0 until numElements) {
            for (j in 0 until typeSize) {
                dest[destOffset + i * typeSize + j] = src[j * numElements + i]
            }
        }
        // Leftover bytes at the end of the block are not shuffled.
        val shuffledBytes = numElements * typeSize
        System.arraycopy(src, shuffledBytes, dest, destOffset + shuffledBytes, blockSize - shuffledBytes)
    }

    /**
     * Decompresses a raw LZ4 block, and returns the number of bytes written.
     */
    private fun lz4Decompress(src: ByteArray, srcOffset: Int, srcLength: Int,
                              dest: ByteArray, destOffset: Int, destLength: Int): Int {
        val srcEnd = srcOffset + srcLength
        val destEnd = destOffset + destLength
        var sp = srcOffset
        var dp = destOffset
        while (sp < srcEnd) {
            val token = src[sp++].toInt() and 0xff

            var literalLength = token ushr 4
            if (literalLength == 15) {
                var b: Int
                do {
                    if (sp >= srcEnd) throw IOException("Corrupt LZ4 data.")
                    b = src[sp++].toInt() and 0xff
                    literalLength += b
                } while (b == 255)
            }
            if (sp + literalLength > srcEnd || dp + literalLength > destEnd) {
                throw IOException("Corrupt LZ4 data.")
            }
            System.arraycopy(src, sp, dest, dp, literalLength)
            sp += literalLength
            dp += literalLength
            if (sp >= srcEnd) {
                // The last sequence contains only literals.
                break
            }

            if (sp + 2 > srcEnd) throw IOException("Corrupt LZ4 data.")
            val offset = (src[sp].toInt() and 0xff) or ((src[sp + 1].toInt() and 0xff) shl 8)
            sp += 2
            var matchLength = token and 0xf
            if (matchLength == 15) {
                var b: Int
                do {
                    if (sp >= srcEnd) throw IOException("Corrupt LZ4 data.")
                    b = src[sp++].toInt() and 0xff
                    matchLength += b
                } while (b == 255)
            }
            matchLength += 4
            var mp = dp - offset
            if (offset == 0 || mp < destOffset || dp + matchLength > destEnd) {
                throw IOException("Corrupt LZ4 data.")
            }
            if (offset >= matchLength) {
                System.arraycopy(dest, mp, dest, dp, matchLength)
                dp += matchLength
            } else {
                // Overlapping match, which repeats the preceding bytes.
                val end = dp + matchLength
                while (dp < end) {
                    dest[dp++] = dest[mp++]
                }
            }
        }
        return dp - destOffset
    }

    companion object {
        private const val HEADER_SIZE = 16
        private const val FLAG_SHUFFLE = 0x1
        private const val FLAG_MEMCPYED = 0x2
        private const val FLAG_BITSHUFFLE = 0x4
        private const val FLAG_DONT_SPLIT = 0x10
        private const val COMPRESSOR_LZ4 = 1
        private const val MAX_SPLITS = 16
        private const val MIN_BUFFERSIZE = 128
    }
}
