package tv.herrin.app

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailPlayLabelTest {
    @Test fun onlyRealCoordinatesAndWatchedPositionAreShown() {
        assertEquals("播放 第2季 第3集 12:35", DetailPlayLabel.text(2, 3, 755000))
        assertEquals("播放 第3集 12:35", DetailPlayLabel.text(episode = 3, positionMs = 755000))
        assertEquals("播放 第3集", DetailPlayLabel.text(episode = 3))
        assertEquals("播放 46:20", DetailPlayLabel.text(positionMs = 2780000))
        assertEquals("播放 01:02:03", DetailPlayLabel.text(positionMs = 3723000))
        assertEquals("播放", DetailPlayLabel.text())
        assertEquals("播放", DetailPlayLabel.text(-1, 0, -100))
    }
}
