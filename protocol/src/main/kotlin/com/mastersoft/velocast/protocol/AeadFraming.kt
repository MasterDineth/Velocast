package com.mastersoft.velocast.protocol

import java.io.InputStream
import java.io.OutputStream

private const val OFF_RECORD_TYPE = 0
private const val OFF_RECORD_FLAGS = 1
private const val OFF_RECORD_EPOCH = 2
private const val OFF_RECORD_COUNTER = 4
private const val OFF_RECORD_LENGTH = 12
private const val RECORD_DATA = 0x01
private const val RECORD_REKEY = 0x02
private const val INITIAL_CIPHER_SCRATCH = 512
private const val MAX_CONSECUTIVE_REKEYS = 2
private val EMPTY_BYTES = ByteArray(0)

/**
 * Writes per-frame `AES-256-GCM` records (`16`-byte AAD header + ciphertext + `16`-byte tag) and
 * emits an authenticated `REKEY` record automatically whenever [SessionCipher.needsRekey] is true.
 */
internal class AeadRecordWriter(private val out: OutputStream, private val cipher: SessionCipher) {
    private val headerScratch = ByteArray(ProtocolV2.AEAD_HEADER_SIZE)
    private var plainScratch = ByteArray(INITIAL_CIPHER_SCRATCH)
    private var wireScratch = ByteArray(
        ProtocolV2.AEAD_HEADER_SIZE + INITIAL_CIPHER_SCRATCH + ProtocolV2.AEAD_TAG_SIZE
    )

    fun writeData(plaintext: ByteArray, offset: Int, length: Int) {
        rekeyIfNeeded()
        writeRecord(RECORD_DATA, plaintext, offset, length)
    }

    fun writeSplitData(
        prefix: ByteArray,
        prefixLength: Int,
        body: ByteArray,
        bodyOffset: Int,
        bodyLength: Int
    ) {
        val total = prefixLength + bodyLength
        if (plainScratch.size < total) {
            plainScratch = ByteArray(total)
        }
        prefix.copyInto(plainScratch, 0, 0, prefixLength)
        if (bodyLength > 0) {
            body.copyInto(plainScratch, prefixLength, bodyOffset, bodyOffset + bodyLength)
        }
        writeData(plainScratch, 0, total)
    }

    fun sendRekeyNow() {
        writeRecord(RECORD_REKEY, EMPTY_BYTES, 0, 0)
        cipher.ratchetKey()
    }

    private fun rekeyIfNeeded() {
        if (cipher.needsRekey) {
            sendRekeyNow()
        }
    }

    private fun writeRecord(recordType: Int, plaintext: ByteArray, offset: Int, length: Int) {
        encodeRecordHeader(
            recordType = recordType,
            epoch = cipher.currentEpoch,
            counter = cipher.nextCounter,
            plaintextLength = length,
            dest = headerScratch
        )
        val wireSize = ProtocolV2.AEAD_HEADER_SIZE + length + ProtocolV2.AEAD_TAG_SIZE
        if (wireScratch.size < wireSize) {
            wireScratch = ByteArray(wireSize)
        }
        headerScratch.copyInto(wireScratch, 0, 0, ProtocolV2.AEAD_HEADER_SIZE)
        cipher.sealInto(
            aad = ByteSlice(headerScratch, 0, ProtocolV2.AEAD_HEADER_SIZE),
            plaintext = ByteSlice(plaintext, offset, length),
            dest = wireScratch,
            destOffset = ProtocolV2.AEAD_HEADER_SIZE
        )
        out.write(wireScratch, 0, wireSize)
    }

    companion object {
        fun encodeRecordHeader(
            recordType: Int,
            epoch: Int,
            counter: Long,
            plaintextLength: Int,
            dest: ByteArray
        ) {
            BigEndian.putU8(dest, OFF_RECORD_TYPE, recordType)
            BigEndian.putU8(dest, OFF_RECORD_FLAGS, 0)
            BigEndian.putU16(dest, OFF_RECORD_EPOCH, epoch)
            BigEndian.putI64(dest, OFF_RECORD_COUNTER, counter)
            BigEndian.putI32(dest, OFF_RECORD_LENGTH, plaintextLength)
        }
    }
}

/**
 * Reads and authenticates per-frame `AES-256-GCM` records from [input] with bounded memory,
 * transparently ratcheting keys on authenticated `REKEY` records.
 */
internal class AeadRecordReader(
    private val input: InputStream,
    private val cipher: SessionCipher,
    private val maxPlaintextLength: Int
) {
    private val headerBytes = ByteArray(ProtocolV2.AEAD_HEADER_SIZE)
    private val cipherBuffer = GrowingBuffer()
    private var plainArray = ByteArray(INITIAL_CIPHER_SCRATCH)

    val plaintext: ByteArray
        get() = plainArray

    /**
     * Reads the next `DATA` record into [plaintext] and returns its decrypted byte count, or `-1`
     * on a clean end-of-stream before the first record byte.
     */
    fun readDataRecord(): Int {
        var rekeyCount = 0
        var dataLength: Int? = null
        while (dataLength == null) {
            val gotHeader = StreamIo.readFully(
                input = input,
                dest = headerBytes,
                offset = 0,
                length = ProtocolV2.AEAD_HEADER_SIZE,
                atBoundary = rekeyCount == 0
            )
            if (!gotHeader) {
                dataLength = -1
            } else {
                dataLength = decodeAndOpenRecord(rekeyCount)
                if (dataLength == null) {
                    rekeyCount++
                }
            }
        }
        return dataLength
    }

    private fun decodeAndOpenRecord(rekeyCount: Int): Int? {
        val recordType = BigEndian.getU8(headerBytes, OFF_RECORD_TYPE)
        val flags = BigEndian.getU8(headerBytes, OFF_RECORD_FLAGS)
        val epoch = BigEndian.getU16(headerBytes, OFF_RECORD_EPOCH)
        val counter = BigEndian.getI64(headerBytes, OFF_RECORD_COUNTER)
        val plainLen = BigEndian.getI32(headerBytes, OFF_RECORD_LENGTH)

        if (plainLen < 0) {
            fail(ErrorCode.MALFORMED_MESSAGE, "Negative AEAD record plaintext length: $plainLen")
        }
        if (plainLen > maxPlaintextLength) {
            fail(
                ErrorCode.MESSAGE_TOO_LARGE,
                "AEAD record plaintext length $plainLen exceeds limit $maxPlaintextLength"
            )
        }

        val cipherLen = plainLen + ProtocolV2.AEAD_TAG_SIZE
        cipherBuffer.fill(input, cipherLen)
        if (plainArray.size < plainLen) {
            plainArray = ByteArray(plainLen)
        }
        cipher.openInto(
            epoch = epoch,
            counter = counter,
            aad = ByteSlice(headerBytes, 0, ProtocolV2.AEAD_HEADER_SIZE),
            ciphertext = ByteSlice(cipherBuffer.array, 0, cipherLen),
            dest = plainArray,
            destOffset = 0
        )
        return classifyAuthenticatedRecord(recordType, flags, plainLen, rekeyCount)
    }

    private fun classifyAuthenticatedRecord(
        recordType: Int,
        flags: Int,
        plainLen: Int,
        rekeyCount: Int
    ): Int? {
        if (flags != 0) {
            fail(ErrorCode.MALFORMED_MESSAGE, "Non-zero AEAD record flags: $flags")
        }
        return when (recordType) {
            RECORD_DATA -> {
                if (plainLen == 0) {
                    fail(ErrorCode.MALFORMED_MESSAGE, "Empty AEAD DATA record")
                }
                plainLen
            }

            RECORD_REKEY -> {
                if (plainLen != 0) {
                    fail(ErrorCode.MALFORMED_MESSAGE, "Non-empty AEAD REKEY record")
                }
                if (rekeyCount >= MAX_CONSECUTIVE_REKEYS) {
                    fail(ErrorCode.UNEXPECTED_MESSAGE, "Excessive consecutive AEAD REKEY records")
                }
                cipher.ratchetKey()
                null
            }

            else -> fail(ErrorCode.UNKNOWN_MESSAGE_TYPE, "Unknown AEAD record type $recordType")
        }
    }
}
