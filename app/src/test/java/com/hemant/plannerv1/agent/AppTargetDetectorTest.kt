package com.hemant.plannerv1.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppTargetDetectorTest {
    @Test
    fun detectsExactInstalledAppNameInGoal() {
        val detection = AppTargetDetector.detect(
            goal = "buy shoes from flipkart",
            installedAppNames = listOf("Flipkart"),
        )

        assertEquals("Flipkart", detection?.appName)
        assertEquals("flipkart", detection?.matchedText)
    }

    @Test
    fun detectsBrandTokenFromLongInstalledAppName() {
        val detection = AppTargetDetector.detect(
            goal = "buy shoes from flipkart",
            installedAppNames = listOf("Flipkart Online Shopping App"),
        )

        assertEquals("Flipkart Online Shopping App", detection?.appName)
        assertEquals("flipkart", detection?.matchedText)
    }

    @Test
    fun rejectsCommonFalsePositiveWords() {
        val detection = AppTargetDetector.detect(
            goal = "drive to office and check outlook near the front door, then zoom in on the photo and check signal",
            installedAppNames = listOf(
                "Google Drive",
                "Microsoft Outlook",
                "Door",
                "Google Maps",
                "Messages",
                "Google Gemini",
                "Threads",
                "Zoom",
                "Google Photos",
                "Signal",
            ),
        )

        assertNull(detection)
    }

    @Test
    fun rejectsPaymentAndFinanceApps() {
        val detection = AppTargetDetector.detect(
            goal = "pay the bill with paypal or cash app",
            installedAppNames = listOf("PayPal", "Cash App", "Google Pay", "Groww", "Upstox"),
        )

        assertNull(detection)
    }

    @Test
    fun rejectsPopularGenericCommerceTravelFoodAndMediaWords() {
        val detection = AppTargetDetector.detect(
            goal = "order food delivery, book flights and hotels, watch a movie, check rewards, then open work docs",
            installedAppNames = listOf(
                "Delivery",
                "Flights",
                "Hotels",
                "Prime Video",
                "Movies",
                "Rewards",
                "Work",
                "Google Docs",
            ),
        )

        assertNull(detection)
    }

    @Test
    fun rejectsGenericTokenFromCompoundAppName() {
        val detection = AppTargetDetector.detect(
            goal = "order eats tonight",
            installedAppNames = listOf("Uber Eats"),
        )

        assertNull(detection)
    }

    @Test
    fun detectsSpecificCompoundAppNameWhenBrandWordIsPresent() {
        val detection = AppTargetDetector.detect(
            goal = "order dinner from uber eats",
            installedAppNames = listOf("Uber Eats"),
        )

        assertEquals("Uber Eats", detection?.appName)
        assertEquals("uber eats", detection?.matchedText)
    }

    @Test
    fun detectsCamelCaseAppNameWhenUserWritesWordsSeparately() {
        val detection = AppTargetDetector.detect(
            goal = "message min on kakao talk",
            installedAppNames = listOf("KakaoTalk"),
        )

        assertEquals("KakaoTalk", detection?.appName)
        assertEquals("kakao talk", detection?.matchedText)
    }

    @Test
    fun rejectsPaymentAppConcatenations() {
        val detection = AppTargetDetector.detect(
            goal = "pay with kakaopay naverpay samsungpay payco or toss",
            installedAppNames = listOf("KakaoPay", "NaverPay", "SamsungPay", "Payco", "Toss"),
        )

        assertNull(detection)
    }

    @Test
    fun doesNotMatchSubstrings() {
        val detection = AppTargetDetector.detect(
            goal = "buy from flipkarting deals",
            installedAppNames = listOf("Flipkart"),
        )

        assertNull(detection)
    }

    @Test
    fun detectsNonCommonExactBrandContainingCommonPrefix() {
        val detection = AppTargetDetector.detect(
            goal = "order dinner on doordash",
            installedAppNames = listOf("DoorDash"),
        )

        assertEquals("DoorDash", detection?.appName)
        assertEquals("doordash", detection?.matchedText)
    }

    @Test
    fun rejectsAmbiguousMatches() {
        val detection = AppTargetDetector.detect(
            goal = "buy socks from amazon",
            installedAppNames = listOf("Amazon", "Amazon Shopping"),
        )

        assertNull(detection)
    }
}
