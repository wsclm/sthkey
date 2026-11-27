package com.something.sthkey.capture.touch

import android.view.Surface
import com.something.sthkey.domain.config.TouchRegionBinding
import com.something.sthkey.domain.config.TouchStickFeel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「触摸区域」类（`CustomTouchRegion.Kind.TOUCH`）在 [TouchFrameTracker] 里的触发判据。
 *
 * ============================================================
 * 它和「按键区域」只差**一条判据**
 * ============================================================
 * | 手指 | 按键区域 | 触摸区域 |
 * |---|---|---|
 * | 按下在区域内 | 触发 | 触发 |
 * | 在区域内长按 | 一直触发 | 一直触发 |
 * | 从区域外**拖进来** | ❌ | ✅ |
 * | 按下在区域内再**拖出去** | 仍算按住 | ✅ 一出去就抬起 |
 *
 * ⚠️ 每一行都拿**同样几何的按键区域**做对照 —— 证明差别是判据，
 * 而不是几何/坐标算错了。
 *
 * ⚠️ 屏幕用正方形（1000×1000），两个坐标系重合，专注状态机本身。
 */
class TouchRegionTouchKindTest {

    private val size = 1000
    private val feel = TouchStickFeel(deadZone = 0f, sensitivity = 1f)

    /** 正中、边长 0.3 → 屏幕上 350..650 */
    private fun region(id: String, cx: Float = 0.5f, cy: Float = 0.5f, side: Float = 0.3f) =
        TouchRegionBinding(slotId = id, centerX = cx, centerY = cy, size = side, pinned = true)

    private fun point(id: Int, x: Float, y: Float) = TouchNativeProtocol.Point(id, x, y)

    private fun TouchFrameTracker.feed(
        vararg points: TouchNativeProtocol.Point,
        regions: List<TouchRegionBinding>,
        touchIds: Set<String> = emptySet(),
        moveIds: Set<String> = emptySet(),
    ) = onFrame(
        rawPoints = points.toList(),
        rotation = Surface.ROTATION_0,
        screenWidth = size,
        screenHeight = size,
        regions = regions,
        moveFeel = feel,
        cameraFeel = feel,
        moveSlotIds = moveIds,
        touchSlotIds = touchIds,
    )

    private val inside = 0.5f to 0.5f
    private val outside = 0.1f to 0.5f

    @Test
    fun `按下在区域内就触发`() {
        val tracker = TouchFrameTracker()
        val regions = listOf(region("t"))
        tracker.feed(point(0, inside.first, inside.second), regions = regions, touchIds = setOf("t"))
        assertEquals(setOf("t"), tracker.pressedSlots())
    }

    @Test
    fun `在区域内长按会一直触发`() {
        val tracker = TouchFrameTracker()
        val regions = listOf(region("t"))
        repeat(10) {
            tracker.feed(point(0, 0.5f, 0.5f), regions = regions, touchIds = setOf("t"))
            assertEquals("第 ${it + 1} 帧", setOf("t"), tracker.pressedSlots())
        }
    }

    @Test
    fun `从区域外拖进来就触发 -- 按键区域做不到`() {
        val regions = listOf(region("t"))

        val touch = TouchFrameTracker()
        touch.feed(point(0, outside.first, outside.second), regions = regions, touchIds = setOf("t"))
        assertTrue("还在区域外，不该触发", touch.pressedSlots().isEmpty())
        touch.feed(point(0, 0.5f, 0.5f), regions = regions, touchIds = setOf("t"))
        assertEquals("拖进区域的这一帧就该触发", setOf("t"), touch.pressedSlots())

        /* 对照:同样的轨迹、同样的几何，但是按键区域 → 不触发 */
        val button = TouchFrameTracker()
        button.feed(point(0, outside.first, outside.second), regions = regions)
        button.feed(point(0, 0.5f, 0.5f), regions = regions)
        assertTrue("按键区域按下那一刻在区域外，归属已定，拖进来不该触发", button.pressedSlots().isEmpty())
    }

    @Test
    fun `按下在区域内再拖出去就抬起 -- 按键区域仍算按住`() {
        val regions = listOf(region("t"))

        val touch = TouchFrameTracker()
        touch.feed(point(0, 0.5f, 0.5f), regions = regions, touchIds = setOf("t"))
        assertEquals(setOf("t"), touch.pressedSlots())
        touch.feed(point(0, 0.1f, 0.5f), regions = regions, touchIds = setOf("t"))
        assertTrue("手指已经出了区域，必须抬起", touch.pressedSlots().isEmpty())

        /* 对照 */
        val button = TouchFrameTracker()
        button.feed(point(0, 0.5f, 0.5f), regions = regions)
        button.feed(point(0, 0.1f, 0.5f), regions = regions)
        assertEquals(setOf("t"), button.pressedSlots())
    }

    @Test
    fun `拖出去再拖回来会再次触发`() {
        val tracker = TouchFrameTracker()
        val regions = listOf(region("t"))
        val ids = setOf("t")
        tracker.feed(point(0, 0.5f, 0.5f), regions = regions, touchIds = ids)
        tracker.feed(point(0, 0.1f, 0.5f), regions = regions, touchIds = ids)
        assertTrue(tracker.pressedSlots().isEmpty())
        tracker.feed(point(0, 0.5f, 0.5f), regions = regions, touchIds = ids)
        assertEquals(setOf("t"), tracker.pressedSlots())
    }

    @Test
    fun `手指抬起就释放`() {
        val tracker = TouchFrameTracker()
        val regions = listOf(region("t"))
        tracker.feed(point(0, 0.5f, 0.5f), regions = regions, touchIds = setOf("t"))
        assertEquals(setOf("t"), tracker.pressedSlots())
        tracker.feed(regions = regions, touchIds = setOf("t"))
        assertTrue(tracker.pressedSlots().isEmpty())
    }

    @Test
    fun `onReleased 之后不再按住`() {
        val tracker = TouchFrameTracker()
        val regions = listOf(region("t"))
        tracker.feed(point(0, 0.5f, 0.5f), regions = regions, touchIds = setOf("t"))
        tracker.onReleased()
        assertTrue(tracker.pressedSlots().isEmpty())
    }

    @Test
    fun `多块触摸区域可以同时触发 -- 各自绑各自的`() {
        val tracker = TouchFrameTracker()
        val regions = listOf(
            region("t1", cx = 0.2f, cy = 0.5f),
            region("t2", cx = 0.8f, cy = 0.5f),
        )
        val ids = setOf("t1", "t2")

        /* 两根手指分别在两块里 */
        tracker.feed(point(0, 0.2f, 0.5f), point(1, 0.8f, 0.5f), regions = regions, touchIds = ids)
        assertEquals(setOf("t1", "t2"), tracker.pressedSlots())

        /* 其中一根离开，只剩另一块 */
        tracker.feed(point(0, 0.2f, 0.5f), point(1, 0.5f, 0.9f), regions = regions, touchIds = ids)
        assertEquals(setOf("t1"), tracker.pressedSlots())
    }

    @Test
    fun `一根手指同时划过两块重叠的触摸区域 -- 两块都触发`() {
        val tracker = TouchFrameTracker()
        val regions = listOf(
            region("t1", cx = 0.5f, cy = 0.5f),
            region("t2", cx = 0.55f, cy = 0.5f),
        )
        tracker.feed(point(0, 0.52f, 0.5f), regions = regions, touchIds = setOf("t1", "t2"))
        assertEquals(setOf("t1", "t2"), tracker.pressedSlots())
    }

    @Test
    fun `同一块区域里有两根手指，一根抬起另一根仍在 -- 仍然触发`() {
        val tracker = TouchFrameTracker()
        val regions = listOf(region("t"))
        val ids = setOf("t")
        tracker.feed(point(0, 0.45f, 0.5f), point(1, 0.55f, 0.5f), regions = regions, touchIds = ids)
        assertEquals(setOf("t"), tracker.pressedSlots())
        tracker.feed(point(1, 0.55f, 0.5f), regions = regions, touchIds = ids)
        assertEquals(setOf("t"), tracker.pressedSlots())
    }

    @Test
    fun `触摸区域与按键区域混用互不干扰`() {
        val tracker = TouchFrameTracker()
        val regions = listOf(
            region("touch", cx = 0.2f, cy = 0.5f),
            region("button", cx = 0.8f, cy = 0.5f),
        )
        val ids = setOf("touch")

        /* 手指 0 按在按键区域里;手指 1 从外面拖进触摸区域 */
        tracker.feed(point(0, 0.8f, 0.5f), point(1, 0.5f, 0.9f), regions = regions, touchIds = ids)
        assertEquals(setOf("button"), tracker.pressedSlots())

        tracker.feed(point(0, 0.8f, 0.5f), point(1, 0.2f, 0.5f), regions = regions, touchIds = ids)
        assertEquals(setOf("button", "touch"), tracker.pressedSlots())

        /* 手指 0 拖出按键区域:按键仍算按住（归属不变），触摸区域不受影响 */
        tracker.feed(point(0, 0.5f, 0.9f), point(1, 0.2f, 0.5f), regions = regions, touchIds = ids)
        assertEquals(setOf("button", "touch"), tracker.pressedSlots())
    }

    @Test
    fun `触摸区域不会被当成轮盘`() {
        val tracker = TouchFrameTracker()
        val regions = listOf(region("stick", cx = 0.2f, cy = 0.5f), region("touch", cx = 0.8f, cy = 0.5f))
        tracker.feed(
            point(0, 0.2f, 0.5f),
            point(1, 0.8f, 0.5f),
            regions = regions,
            touchIds = setOf("touch"),
            moveIds = setOf("stick"),
        )
        /* 轮盘不进 pressedSlots（它走 moveRegions），触摸区域进 */
        assertEquals(setOf("touch"), tracker.pressedSlots())
        assertTrue(tracker.snapshot().moveRegions.containsKey("stick"))
        assertFalse(tracker.snapshot().moveRegions.containsKey("touch"))
    }

    @Test
    fun `没声明触摸区域时行为与加这个参数之前一致`() {
        val tracker = TouchFrameTracker()
        val regions = listOf(region("t"))
        tracker.feed(point(0, 0.1f, 0.5f), regions = regions)
        tracker.feed(point(0, 0.5f, 0.5f), regions = regions)
        assertTrue(tracker.pressedSlots().isEmpty())
    }

    @Test
    fun `区域边界上也算在里面`() {
        /* 350..650 的闭区间:与按键区域用同一个 regionContains */
        val tracker = TouchFrameTracker()
        val regions = listOf(region("t"))
        tracker.feed(point(0, 0.35f, 0.5f), regions = regions, touchIds = setOf("t"))
        assertEquals(setOf("t"), tracker.pressedSlots())
    }
}
