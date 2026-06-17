package com.hemant.plannerv1.model

import com.hemant.plannerv1.agent.UiActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelOutputParserTest {
    private val parser = ModelOutputParser()

    @Test
    fun parsesValidClick() {
        val action = parser.parse(
            """{"action":"click","bounding_box":[10,20,30,40],"text":null,"app_name":null,"reason":"tap search","done":false}""",
        )

        assertEquals(UiActionType.CLICK, action.type)
        assertEquals(listOf(10.0, 20.0, 30.0, 40.0), action.boundingBox)
    }

    @Test
    fun parsesValidScrollUp() {
        val action = parser.parse(
            """{"action":"scroll_up","bounding_box":null,"text":null,"app_name":null,"reason":"scroll","done":false}""",
        )

        assertEquals(UiActionType.SCROLL_UP, action.type)
    }

    @Test
    fun parsesValidOpenApp() {
        val action = parser.parse(
            """{"action":"open_app","bounding_box":null,"text":null,"app_name":"YouTube","reason":"launching app","done":false}""",
        )

        assertEquals(UiActionType.OPEN_APP, action.type)
        assertEquals("YouTube", action.appName)
    }

    @Test
    fun parsesDoneWithOmittedDoneFlag() {
        val action = parser.parse(
            """{"action":"done","reason":"task finished"}""",
        )

        assertEquals(UiActionType.DONE, action.type)
        assertEquals(true, action.done)
    }

    @Test
    fun parsesJsonAfterThoughtChannel() {
        val action = parser.parse(
            """<|channel>thought
                The close button is visible near the top-right, so a click is appropriate.
                <channel|>{"action":"click","bounding_box":[470,82,490,100],"reason":"tap close"}""".trimIndent(),
        )

        assertEquals(UiActionType.CLICK, action.type)
        assertEquals(listOf(470.0, 82.0, 490.0, 100.0), action.boundingBox)
    }

    @Test
    fun rejectsMarkdownWrappedJson() {
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(
                """
                ```json
                {"action":"done","reason":"complete","done":true}
                ```
                """.trimIndent(),
            )
        }
    }

    @Test
    fun rejectsClickWithoutCoordinates() {
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(
                """{"action":"click","bounding_box":null,"text":null,"app_name":null,"reason":"tap","done":false}""",
            )
        }
    }

    @Test
    fun rejectsTypeTextWithoutText() {
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(
                """{"action":"type_text","bounding_box":[0,0,10,10],"reason":"enter query"}""",
            )
        }
    }

    @Test
    fun rejectsBackWithText() {
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(
                """{"action":"back","text":"oops","reason":"go back","done":false}""",
            )
        }
    }

    @Test
    fun rejectsDoneWithCoordinates() {
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(
                """{"action":"done","bounding_box":[0,0,10,10],"reason":"finished","done":true}""",
            )
        }
    }

    @Test
    fun rejectsExtraKeys() {
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(
                """{"action":"done","reason":"complete","done":true,"extra":1}""",
            )
        }
    }
}
