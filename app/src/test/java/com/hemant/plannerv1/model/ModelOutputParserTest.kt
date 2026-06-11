package com.hemant.plannerv1.model

import com.hemant.plannerv1.agent.SwipeDirection
import com.hemant.plannerv1.agent.UiActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelOutputParserTest {
    private val parser = ModelOutputParser()

    @Test
    fun parsesValidClick() {
        val action = parser.parse(
            """{"action":"click","x":42,"y":128,"text":null,"direction":null,"reason":"tap search","done":false}""",
        )

        assertEquals(UiActionType.CLICK, action.type)
        assertEquals(42.0, action.x!!, 0.0)
        assertEquals(128.0, action.y!!, 0.0)
    }

    @Test
    fun parsesValidSwipe() {
        val action = parser.parse(
            """{"action":"swipe","x":null,"y":null,"text":null,"direction":"up","reason":"scroll","done":false}""",
        )

        assertEquals(UiActionType.SWIPE, action.type)
        assertEquals(SwipeDirection.UP, action.direction)
    }

    @Test
    fun rejectsMarkdownWrappedJson() {
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(
                """
                ```json
                {"action":"done","x":null,"y":null,"text":null,"direction":null,"reason":"complete","done":true}
                ```
                """.trimIndent(),
            )
        }
    }

    @Test
    fun rejectsClickWithoutCoordinates() {
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(
                """{"action":"click","x":null,"y":null,"text":null,"direction":null,"reason":"tap","done":false}""",
            )
        }
    }

    @Test
    fun rejectsExtraKeys() {
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(
                """{"action":"done","x":null,"y":null,"text":null,"direction":null,"reason":"complete","done":true,"extra":1}""",
            )
        }
    }
}
