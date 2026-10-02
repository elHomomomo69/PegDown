package com.dpm.pegdown.data

import android.content.ContentResolver
import android.content.ContextWrapper
import android.net.Uri
import android.provider.MediaStore
import com.dpm.pegdown.model.TourLogEntry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TourExporterSaveTest {

    @Test
    fun `failed GPX write removes partial entry and reports an error`() {
        val resolver = mockk<ContentResolver>(relaxed = true)
        val uri = Uri.parse("content://downloads/tour")
        every {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, any())
        } returns uri
        every { resolver.openOutputStream(uri) } returns null
        every { resolver.delete(uri, null, null) } returns 1

        val appContext = RuntimeEnvironment.getApplication()
        val context = object : ContextWrapper(appContext) {
            override fun getContentResolver(): ContentResolver = resolver
        }

        TourExporter(context).saveTourToGpx(
            "Tour",
            listOf(
                TourLogEntry(
                    timestamp = "2024-01-01 12:00:00",
                    leanAngleLeft = 0.0,
                    leanAngleRight = 0.0,
                    acceleration = 0.0,
                    braking = 0.0,
                    lat = 52.0,
                    lon = 13.0,
                    altitude = 150.0,
                    speed = 50.0
                )
            )
        )

        verify(exactly = 1) { resolver.delete(uri, null, null) }
        assertTrue(ShadowToast.showedToast("Error saving GPX file!"))
        assertFalse(ShadowToast.showedToast("Saved to Downloads!"))
    }
}
