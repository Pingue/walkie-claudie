package com.bookmari.walkie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelsTest {
    @Test fun nextIsTheFollowingPort() = assertEquals(7702, Channels.nextPort(listOf(7700, 7702, 7705), 7700))

    @Test fun wrapsAroundToTheFirst() = assertEquals(7700, Channels.nextPort(listOf(7700, 7702, 7705), 7705))

    @Test fun unsortedAndDuplicatePortsAreFine() = assertEquals(7703, Channels.nextPort(listOf(7705, 7700, 7703, 7700), 7701))

    @Test fun skipsTheCurrentChannelAndJumpsFromAClosedOne() = assertEquals(7705, Channels.nextPort(listOf(7700, 7705), 7702)) // 7702 closed, not in the list

    @Test fun noOtherChannelMeansNull() {
        assertNull(Channels.nextPort(listOf(7700), 7700))
        assertNull(Channels.nextPort(emptyList(), 7700))
    }
}
