package com.mastersoft.velocast.server

import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.UndeclaredThrowableException

/**
 * Enumerated capabilities exercised by [CapabilityProbe] during the shell-user spike (SEC-04, CQ-03).
 */
enum class CapabilityId(val wireName: String) {
    VIRTUAL_DISPLAY("virtual_display"),
    INPUT_INJECTION("input_injection"),
    NOTIFICATION_SHADE("notification_shade"),
    ROTATION("rotation"),
    DISPLAY_POWER("display_power"),
    WAKE("wake"),
    CLIPBOARD("clipboard")
}

/**
 * Typed outcome of probing a single privileged Android capability.
 */
sealed interface ProbeResult {
    data object Supported : ProbeResult

    data class Unsupported(val reason: String) : ProbeResult

    data class Denied(val permission: String) : ProbeResult
}

/**
 * Process identity and device metadata attached to each JSON probe line.
 */
data class ProbeEnvironmentInfo(
    val sdkInt: Int,
    val uid: Int,
    val packageName: String,
    val model: String
)

/**
 * Single capability probe record serialized as one JSON object per line.
 */
data class CapabilityProbeRecord(
    val capability: CapabilityId,
    val result: ProbeResult,
    val detail: String? = null
) {
    fun toJsonLine(env: ProbeEnvironmentInfo): String = buildString {
        append('{')
        appendField("capability", capability.wireName)
        when (val outcome = result) {
            ProbeResult.Supported -> {
                append(',')
                appendField("status", STATUS_SUPPORTED)
            }

            is ProbeResult.Unsupported -> {
                append(',')
                appendField("status", STATUS_UNSUPPORTED)
                append(',')
                appendField("reason", outcome.reason)
            }

            is ProbeResult.Denied -> {
                append(',')
                appendField("status", STATUS_DENIED)
                append(',')
                appendField("permission", outcome.permission)
            }
        }
        if (!detail.isNullOrBlank()) {
            append(',')
            appendField("detail", detail)
        }
        append(",\"sdk\":")
        append(env.sdkInt)
        append(",\"uid\":")
        append(env.uid)
        append(',')
        appendField("package", env.packageName)
        append(',')
        appendField("model", env.model)
        append('}')
    }

    private fun StringBuilder.appendField(key: String, value: String) {
        appendJsonString(key)
        append(':')
        appendJsonString(value)
    }

    private fun StringBuilder.appendJsonString(raw: String) {
        append('"')
        for (ch in raw) {
            when (ch) {
                '\\' -> append("\\\\")

                '"' -> append("\\\"")

                '\n' -> append("\\n")

                '\r' -> append("\\r")

                '\t' -> append("\\t")

                '\b' -> append("\\b")

                '\u000C' -> append("\\f")

                else -> {
                    if (ch.code < JSON_CONTROL_LIMIT) {
                        append("\\u")
                        append(ch.code.toString(HEX_RADIX).padStart(UNICODE_HEX_LEN, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
        }
        append('"')
    }

    private companion object {
        const val STATUS_SUPPORTED = "supported"
        const val STATUS_UNSUPPORTED = "unsupported"
        const val STATUS_DENIED = "denied"
        const val JSON_CONTROL_LIMIT = 0x20
        const val HEX_RADIX = 16
        const val UNICODE_HEX_LEN = 4
    }
}

/**
 * Thrown by a [CapabilityProbeBackend] when a probe detects an explicit permission denial without
 * a [SecurityException] (for example `InputManager.injectInputEvent` returning `false`).
 */
class ProbeDeniedException(
    val permission: String,
    message: String = permission,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Platform operations exercised one by one by [CapabilityProbe].
 */
interface CapabilityProbeBackend {
    val environmentInfo: ProbeEnvironmentInfo

    fun probeVirtualDisplay(): String?

    fun probeInputInjection(): String?

    fun probeNotificationShade(): String?

    fun probeRotation(): String?

    fun probeDisplayPower(): String?

    fun probeWakeKey(): String?

    fun probeClipboard(): String?
}

/**
 * Exercises each privileged server capability in isolation and maps platform outcomes to
 * [ProbeResult.Supported], [ProbeResult.Unsupported], or [ProbeResult.Denied] without altering
 * production server behaviour.
 */
class CapabilityProbe(private val backend: CapabilityProbeBackend) {

    fun runAll(out: PrintStream? = null): List<CapabilityProbeRecord> {
        val env = backend.environmentInfo
        val probes: List<Pair<CapabilityId, () -> String?>> = listOf(
            CapabilityId.VIRTUAL_DISPLAY to { backend.probeVirtualDisplay() },
            CapabilityId.INPUT_INJECTION to { backend.probeInputInjection() },
            CapabilityId.NOTIFICATION_SHADE to { backend.probeNotificationShade() },
            CapabilityId.ROTATION to { backend.probeRotation() },
            CapabilityId.DISPLAY_POWER to { backend.probeDisplayPower() },
            CapabilityId.WAKE to { backend.probeWakeKey() },
            CapabilityId.CLIPBOARD to { backend.probeClipboard() }
        )
        val records = ArrayList<CapabilityProbeRecord>(probes.size)
        for ((capability, block) in probes) {
            val record = runSingle(capability, block)
            records += record
            if (out != null) {
                out.println(record.toJsonLine(env))
                out.flush()
            }
        }
        return records
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runSingle(capability: CapabilityId, block: () -> String?): CapabilityProbeRecord =
        try {
            val detail = block()
            CapabilityProbeRecord(
                capability = capability,
                result = ProbeResult.Supported,
                detail = detail?.takeIf { it.isNotBlank() }
            )
        } catch (t: Throwable) {
            if (t is Error && t !is LinkageError) {
                throw t
            }
            val mapped = mapThrowable(t)
            val detail = if (mapped is ProbeResult.Denied) {
                extractDeniedDetail(t)
            } else {
                null
            }
            CapabilityProbeRecord(
                capability = capability,
                result = mapped,
                detail = detail
            )
        }

    companion object {
        private const val MAX_UNWRAP_DEPTH = 12
        private val QUALIFIED_PERMISSION_REGEX = Regex("""android\.permission\.[A-Z0-9_]+""")
        private val NAMED_PERMISSION_BEFORE_WORD =
            Regex("""\b([A-Z][A-Z0-9_]{2,})\s+permission\b""")
        private val NAMED_PERMISSION_AFTER_WORD = Regex("""\bpermission\s+([A-Z][A-Z0-9_]{2,})\b""")

        fun mapThrowable(throwable: Throwable): ProbeResult {
            val unwrapped = unwrapReflection(throwable)
            val denied = findDeniedCause(unwrapped)
            if (denied != null) {
                return when (denied) {
                    is ProbeDeniedException -> ProbeResult.Denied(denied.permission)
                    else -> ProbeResult.Denied(extractPermission(denied.message, denied))
                }
            }
            val reason = formatUnsupportedReason(unwrapped)
            return ProbeResult.Unsupported(reason)
        }

        internal fun extractPermission(
            message: String?,
            fallbackThrowable: Throwable? = null
        ): String {
            val clean = message?.trim().orEmpty()
            val qualified = QUALIFIED_PERMISSION_REGEX.find(clean)?.value
            val beforeWord = NAMED_PERMISSION_BEFORE_WORD.find(clean)?.groupValues?.getOrNull(1)
                ?.takeIf { it.contains('_') }
            val afterWord = NAMED_PERMISSION_AFTER_WORD.find(clean)?.groupValues?.getOrNull(1)
                ?.takeIf { it.contains('_') }
            return qualified
                ?: beforeWord
                ?: afterWord
                ?: clean.ifEmpty { fallbackThrowable?.javaClass?.simpleName ?: "SecurityException" }
        }

        private fun unwrapReflection(throwable: Throwable): Throwable {
            var current = throwable
            var depth = 0
            while (depth < MAX_UNWRAP_DEPTH) {
                val next = when (current) {
                    is InvocationTargetException -> current.targetException
                    is UndeclaredThrowableException -> current.undeclaredThrowable
                    else -> null
                } ?: break
                current = next
                depth++
            }
            return current
        }

        private fun findDeniedCause(root: Throwable): Throwable? {
            var current: Throwable? = root
            var depth = 0
            while (current != null && depth < MAX_UNWRAP_DEPTH) {
                val unwrapped = unwrapReflection(current)
                if (unwrapped is ProbeDeniedException ||
                    unwrapped is SecurityException ||
                    unwrapped is InjectionDeniedException
                ) {
                    return unwrapped
                }
                current = unwrapped.cause
                depth++
            }
            return null
        }

        private fun extractDeniedDetail(throwable: Throwable): String? {
            val denied = findDeniedCause(unwrapReflection(throwable)) ?: return null
            val message = denied.message?.trim()
            return if (message.isNullOrEmpty()) null else message
        }

        private fun formatUnsupportedReason(root: Throwable): String {
            val leaf = unwrapLeafIfGenericWrapper(root)
            val msg = leaf.message?.trim().orEmpty()
            return when {
                leaf is UnsupportedPlatformException && msg.isNotEmpty() -> msg
                msg.isNotEmpty() -> "${leaf.javaClass.simpleName}: $msg"
                else -> leaf.javaClass.simpleName
            }
        }

        private fun unwrapLeafIfGenericWrapper(root: Throwable): Throwable {
            var current = root
            var depth = 0
            while (current is UnsupportedPlatformException &&
                current.cause != null &&
                depth < MAX_UNWRAP_DEPTH
            ) {
                val cause = unwrapReflection(current.cause!!)
                if (cause === current) break
                current = cause
                depth++
            }
            return if (root is UnsupportedPlatformException && current === root) {
                root
            } else if (root is UnsupportedPlatformException && current !is SecurityException) {
                root
            } else {
                current
            }
        }
    }
}
