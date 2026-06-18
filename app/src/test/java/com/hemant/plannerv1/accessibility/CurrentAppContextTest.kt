package com.hemant.plannerv1.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentAppContextTest {
    @Test
    fun preferredNameUsesAppNameFirst() {
        val context = CurrentAppContext(
            appName = "Chrome",
            packageName = "com.android.chrome",
        )

        assertEquals("Chrome", context.preferredName)
    }

    @Test
    fun preferredNameFallsBackToPackageName() {
        val context = CurrentAppContext(
            appName = null,
            packageName = "com.android.chrome",
        )

        assertEquals("com.android.chrome", context.preferredName)
    }

    @Test
    fun preferredNameFallsBackWhenAppNameIsUnknown() {
        val context = CurrentAppContext(
            appName = "unknown",
            packageName = "com.android.chrome",
        )

        assertEquals("com.android.chrome", context.preferredName)
    }
}
