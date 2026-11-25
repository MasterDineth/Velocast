package com.mastersoft.velocast.server

import android.view.Surface
import com.mastersoft.velocast.protocol.DisplayGeometry
import com.mastersoft.velocast.server.platform.DisplayBridgeBackend
import com.mastersoft.velocast.server.platform.ReflectionDisplayBridgeBackend
import com.mastersoft.velocast.server.platform.VirtualDisplayBridge
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class VirtualDisplayBridgeTest {
    @Test
    fun throwsUnsupportedPlatformExceptionWhenBackendCannotCreateDisplay() {
        val logger = Logger(stderr = PrintStream(ByteArrayOutputStream()))
        val cause = NoSuchMethodException("createVirtualDisplay hidden")
        val backend = object : DisplayBridgeBackend {
            override fun queryDefaultDisplayGeometry(): DisplayGeometry =
                DisplayGeometry(1080, 2400, 0)

            override fun createMirrorVirtualDisplay(
                name: String,
                width: Int,
                height: Int,
                surface: Surface
            ): AutoCloseable =
                throw UnsupportedPlatformException("No DisplayManager mirror API", cause)
        }
        val bridge = VirtualDisplayBridge(logger, backend)
        assertEquals(DisplayGeometry(1080, 2400, 0), bridge.queryDefaultDisplayGeometry())

        try {
            ReflectionDisplayBridgeBackend(logger).queryDefaultDisplayGeometry()
            fail("Expected UnsupportedPlatformException on JVM stub")
        } catch (e: UnsupportedPlatformException) {
            assertSame(UnsupportedPlatformException::class.java, e::class.java)
        }

        try {
            backend.createMirrorVirtualDisplay("test", 1080, 2400, unsafeDummySurface())
            fail("Expected UnsupportedPlatformException")
        } catch (e: UnsupportedPlatformException) {
            assertSame(cause, e.cause)
        }
    }

    private fun unsafeDummySurface(): Surface {
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, Surface::class.java) as Surface
    }
}
