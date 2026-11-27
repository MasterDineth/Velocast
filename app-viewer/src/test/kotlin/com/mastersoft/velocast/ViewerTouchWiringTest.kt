package com.mastersoft.velocast

import com.mastersoft.velocast.client.ViewportMapper
import com.mastersoft.velocast.protocol.DisplayGeometry
import com.mastersoft.velocast.protocol.VideoSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerTouchWiringTest {
    @Test
    fun `viewport mapper maps letterboxed video size into unit coordinates`() {
        val mapper = ViewportMapper()
        mapper.updateContainerSize(1000, 1000)
        mapper.updateVideoSize(VideoSize(500, 1000, DisplayGeometry(1080, 2160, 0)))

        val rect = mapper.letterboxRect
        assertEquals(250f, rect.left, 0.01f)
        assertEquals(0f, rect.top, 0.01f)
        assertEquals(500f, rect.width, 0.01f)
        assertEquals(1000f, rect.height, 0.01f)

        assertNull(mapper.mapTouchDown(100f, 500f))
        val center = mapper.mapTouchDown(500f, 500f)
        assertNotNull(center)
        assertEquals(0.5f, center!!.x, 0.001f)
        assertEquals(0.5f, center.y, 0.001f)
    }
}
