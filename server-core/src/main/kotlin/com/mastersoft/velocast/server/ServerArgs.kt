package com.mastersoft.velocast.server

import com.mastersoft.velocast.protocol.ProtocolV2
import java.net.InetAddress
import java.nio.file.Path
import java.nio.file.Paths

data class ServerArgs(
    val port: Int = ProtocolV2.DEFAULT_PORT,
    val bindAddress: InetAddress = IpLiterals.LOOPBACK_V4,
    val stateDir: Path? = null,
    val secretFromStdin: Boolean = false,
    val logLevel: LogLevel = LogLevel.INFO
) {
    companion object {
        private const val MAX_PORT = 65_535

        fun parse(args: Array<String>): ServerArgs {
            var port = ProtocolV2.DEFAULT_PORT
            var bindAddress = IpLiterals.LOOPBACK_V4
            var stateDir: Path? = null
            var secretFromStdin = false
            var logLevel = LogLevel.INFO

            var index = 0
            while (index < args.size) {
                when (val flag = args[index]) {
                    "--port" -> port = parsePort(nextArgValue(args, index++, flag))

                    "--bind" -> bindAddress = parseBind(nextArgValue(args, index++, flag))

                    "--state-dir" -> {
                        val raw = nextArgValue(args, index++, flag)
                        require(raw.isNotBlank()) { "--state-dir must not be blank" }
                        stateDir = Paths.get(raw)
                    }

                    "--secret-from-stdin" -> secretFromStdin = true

                    "--log-level" -> {
                        val raw = nextArgValue(args, index++, flag)
                        logLevel = LogLevel.parse(raw)
                    }

                    else -> throw IllegalArgumentException("Unknown argument: $flag")
                }
                index++
            }

            return ServerArgs(
                port = port,
                bindAddress = bindAddress,
                stateDir = stateDir,
                secretFromStdin = secretFromStdin,
                logLevel = logLevel
            )
        }

        private fun parsePort(raw: String): Int {
            val parsed = raw.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid --port value: $raw")
            require(parsed in 0..MAX_PORT) { "--port out of range 0..$MAX_PORT: $raw" }
            return parsed
        }

        private fun nextArgValue(args: Array<String>, flagIndex: Int, flag: String): String {
            val valueIndex = flagIndex + 1
            require(valueIndex < args.size) { "Missing value for $flag" }
            return args[valueIndex]
        }

        private fun parseBind(raw: String): InetAddress = if (raw == "loopback") {
            IpLiterals.LOOPBACK_V4
        } else {
            IpLiterals.parse(raw)
        }
    }
}

/**
 * Pure-JVM numeric IPv4 / IPv6 literal parser calling [InetAddress.getByAddress] so `--bind`
 * validation never triggers a DNS lookup.
 */
internal object IpLiterals {
    private const val IPV4_BYTES = 4
    private const val MAX_OCTET_DIGITS = 3
    private const val IPV4_MAX_OCTET = 255
    private const val IPV6_BYTES = 16
    private const val IPV6_GROUPS = 8
    private const val HEX_SHIFT = 4
    private const val MAX_HEX_GROUP_LEN = 4
    private const val BYTE_MASK = 0xFF
    private const val BYTE_SHIFT = 8
    private const val DECIMAL_BASE = 10

    val LOOPBACK_V4: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    fun parse(spec: String): InetAddress {
        val raw = parseIpv4OrNull(spec) ?: parseIpv6OrNull(spec)
            ?: throw IllegalArgumentException("Invalid bind IP address: $spec")
        return InetAddress.getByAddress(raw)
    }

    private fun parseIpv4OrNull(spec: String): ByteArray? {
        val parts = spec.split('.')
        var result: ByteArray? = null
        if (parts.size == IPV4_BYTES) {
            val bytes = ByteArray(IPV4_BYTES)
            var valid = true
            for (i in 0 until IPV4_BYTES) {
                val octet = parseDecimalOctet(parts[i])
                if (octet == null) {
                    valid = false
                } else {
                    bytes[i] = octet.toByte()
                }
            }
            if (valid) result = bytes
        }
        return result
    }

    private fun parseDecimalOctet(part: String): Int? {
        var result: Int? = null
        if (part.isNotEmpty() && part.length <= MAX_OCTET_DIGITS) {
            var value = 0
            var valid = true
            for (ch in part) {
                if (ch !in '0'..'9') {
                    valid = false
                } else {
                    value = value * DECIMAL_BASE + (ch - '0')
                }
            }
            if (valid && value <= IPV4_MAX_OCTET) result = value
        }
        return result
    }

    private fun parseIpv6OrNull(spec: String): ByteArray? {
        var result: ByteArray? = null
        if (spec.contains(':')) {
            val doubleColonIdx = spec.indexOf("::")
            val hasCompression = doubleColonIdx >= 0
            val multipleCompressions = hasCompression && spec.indexOf("::", doubleColonIdx + 2) >= 0
            if (!multipleCompressions) {
                val headText = if (hasCompression) spec.substring(0, doubleColonIdx) else spec
                val tailText = if (hasCompression) spec.substring(doubleColonIdx + 2) else ""
                val headGroups = if (headText.isEmpty()) emptyList() else headText.split(':')
                val tailGroups = if (tailText.isEmpty()) emptyList() else tailText.split(':')
                result = assembleIpv6Bytes(headGroups, tailGroups, hasCompression)
            }
        }
        return result
    }

    private fun assembleIpv6Bytes(
        headGroups: List<String>,
        tailGroups: List<String>,
        hasCompression: Boolean
    ): ByteArray? {
        val parsedHead = parseHexGroups(headGroups)
        val parsedTail = parseHexGroups(tailGroups)
        var result: ByteArray? = null
        if (parsedHead != null && parsedTail != null) {
            val total = parsedHead.size + parsedTail.size
            val validCount = if (hasCompression) total < IPV6_GROUPS else total == IPV6_GROUPS
            if (validCount) {
                val groups = IntArray(IPV6_GROUPS)
                for (i in parsedHead.indices) {
                    groups[i] = parsedHead[i]
                }
                val tailStart = IPV6_GROUPS - parsedTail.size
                for (i in parsedTail.indices) {
                    groups[tailStart + i] = parsedTail[i]
                }
                val bytes = ByteArray(IPV6_BYTES)
                for (i in 0 until IPV6_GROUPS) {
                    bytes[i * 2] = ((groups[i] ushr BYTE_SHIFT) and BYTE_MASK).toByte()
                    bytes[i * 2 + 1] = (groups[i] and BYTE_MASK).toByte()
                }
                result = bytes
            }
        }
        return result
    }

    private fun parseHexGroups(groups: List<String>): IntArray? {
        val out = IntArray(groups.size)
        var valid = true
        for (i in groups.indices) {
            val parsed = parseSingleHexGroup(groups[i])
            if (parsed == null) {
                valid = false
            } else {
                out[i] = parsed
            }
        }
        return if (valid) out else null
    }

    private fun parseSingleHexGroup(group: String): Int? {
        var result: Int? = null
        if (group.isNotEmpty() && group.length <= MAX_HEX_GROUP_LEN) {
            var value = 0
            var valid = true
            for (ch in group) {
                val digit = hexDigit(ch)
                if (digit == null) {
                    valid = false
                } else {
                    value = (value shl HEX_SHIFT) or digit
                }
            }
            if (valid) result = value
        }
        return result
    }

    private fun hexDigit(ch: Char): Int? = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + DECIMAL_BASE
        in 'A'..'F' -> ch - 'A' + DECIMAL_BASE
        else -> null
    }
}
