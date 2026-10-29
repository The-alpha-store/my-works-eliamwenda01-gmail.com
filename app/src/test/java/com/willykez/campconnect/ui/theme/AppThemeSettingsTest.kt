package com.willykez.campconnect.ui.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class AppThemeSettingsTest {
  @Test fun themeModeCanCycleSystemLightDarkSystem() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    AppThemeSettings.set(context, AppThemeMode.SYSTEM)
    assertEquals(AppThemeMode.SYSTEM, AppThemeSettings.mode)
    AppThemeSettings.set(context, AppThemeMode.LIGHT)
    assertEquals(AppThemeMode.LIGHT, AppThemeSettings.mode)
    AppThemeSettings.set(context, AppThemeMode.DARK)
    assertEquals(AppThemeMode.DARK, AppThemeSettings.mode)
    AppThemeSettings.set(context, AppThemeMode.SYSTEM)
    assertEquals(AppThemeMode.SYSTEM, AppThemeSettings.mode)
  }
}
