package com.dirk.kalshiodds.decision

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ScalpControlPrefsTest {
    @Test fun savedSettingsSurviveControllerRecreationAndResetIsIsolated() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("scalp_manual_v1", 0).edit().clear().commit()
        val c = ScalpControlSpec.cheapEarly("sf41")
        ScalpControls(PrefsScalpControlStore(context)).apply("sf41", c)
        val next = ScalpControls(PrefsScalpControlStore(context))
        assertEquals(c, next.get("sf41"))
        next.apply("sf42", ScalpControlSpec.defaults("sf42"))
        next.reset("sf41")
        assertNull(ScalpControls(PrefsScalpControlStore(context)).get("sf41"))
        assertNotNull(ScalpControls(PrefsScalpControlStore(context)).get("sf42"))
    }
}
