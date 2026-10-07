package com.mcp.toolbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LineEditTest {
    @Test
    fun replacesLineRangeAndPreservesCrLf() {
        val result = applyLineRangeEdit(
            "one\r\ntwo\r\nthree\r\n",
            startLine = 2,
            endLine = 2,
            newContent = "TWO\nplus"
        )

        assertEquals("one\r\nTWO\r\nplus\r\nthree\r\n", result.text)
        assertEquals("two", result.oldText)
        assertEquals("TWO\r\nplus", result.newText)
    }

    @Test
    fun replacesMultipleLinesAndCanRemoveContent() {
        val result = applyLineRangeEdit("a\nb\nc", 1, 2, "")

        assertEquals("c", result.text)
        assertEquals("a\nb", result.oldText)
        assertEquals("", result.newText)
    }

    @Test
    fun removingTheOnlyLineLeavesAnEmptyFile() {
        assertEquals("", applyLineRangeEdit("only\r\n", 1, 1, "").text)
    }

    @Test
    fun rejectsOutOfRange() {
        assertFailsWith<IllegalArgumentException> {
            applyLineRangeEdit("a\nb", 2, 3, "x")
        }
    }
}
