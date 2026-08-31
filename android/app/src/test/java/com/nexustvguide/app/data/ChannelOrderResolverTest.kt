package com.nexustvguide.app.data

import com.nexustvguide.app.data.model.ChannelDto
import com.nexustvguide.app.data.model.ChannelOrderPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelOrderResolverTest {

    private fun createChannel(id: String, name: String = id, sortOrder: Int = 0): ChannelDto {
        return ChannelDto(
            id = id,
            sourceId = id,
            name = name,
            logoUrl = "https://example.com/$id.png",
            inNlziet = true,
            nlzietSlug = id,
            sortOrder = sortOrder
        )
    }

    @Test
    fun `empty preference returns backend order unchanged`() {
        val backend = listOf(
            createChannel("npo1"),
            createChannel("npo2"),
            createChannel("npo3")
        )
        val prefs = ChannelOrderPreferences()

        val result = ChannelOrderResolver.apply(backend, prefs)

        assertEquals(listOf("npo1", "npo2", "npo3"), result.map { it.id })
    }

    @Test
    fun `preference contains id that backend no longer delivers - ignored by resolver`() {
        val backend = listOf(
            createChannel("npo1"),
            createChannel("npo2")
        )
        val prefs = ChannelOrderPreferences(
            orderedIds = listOf("npo2", "old_channel", "npo1")
        )

        val result = ChannelOrderResolver.apply(backend, prefs)

        assertEquals(listOf("npo2", "npo1"), result.map { it.id })
    }

    @Test
    fun `backend delivers channel not in preference - inserted after backend predecessor`() {
        val backend = listOf(
            createChannel("npo1"),
            createChannel("npo2"),
            createChannel("npo3"),
            createChannel("rtl4")
        )
        // User had reordered npo3 before npo1, rtl4 is ordered, npo2 is new in backend
        val prefs = ChannelOrderPreferences(
            orderedIds = listOf("npo3", "npo1", "rtl4")
        )

        val result = ChannelOrderResolver.apply(backend, prefs)

        // npo2's predecessor in backend is npo1. In result list [npo3, npo1, rtl4],
        // npo2 should be inserted directly after npo1.
        assertEquals(listOf("npo3", "npo1", "npo2", "rtl4"), result.map { it.id })
    }

    @Test
    fun `multiple new channels next to each other in backend - retain relative backend order`() {
        val backend = listOf(
            createChannel("npo1"),
            createChannel("new1"),
            createChannel("new2"),
            createChannel("npo2")
        )
        val prefs = ChannelOrderPreferences(
            orderedIds = listOf("npo2", "npo1")
        )

        val result = ChannelOrderResolver.apply(backend, prefs)

        // Predecessor of new1 is npo1 -> inserted after npo1 in [npo2, npo1] -> [npo2, npo1, new1]
        // Predecessor of new2 in backend is new1 -> inserted after new1 -> [npo2, npo1, new1, new2]
        assertEquals(listOf("npo2", "npo1", "new1", "new2"), result.map { it.id })
    }

    @Test
    fun `new channel at the front of backend list - placed at front of result`() {
        val backend = listOf(
            createChannel("new_first"),
            createChannel("npo1"),
            createChannel("npo2")
        )
        val prefs = ChannelOrderPreferences(
            orderedIds = listOf("npo2", "npo1")
        )

        val result = ChannelOrderResolver.apply(backend, prefs)

        // new_first has no predecessors in backend, so it is placed at index 0
        assertEquals(listOf("new_first", "npo2", "npo1"), result.map { it.id })
    }

    @Test
    fun `all channels hidden - returns empty list`() {
        val backend = listOf(
            createChannel("npo1"),
            createChannel("npo2")
        )
        val prefs = ChannelOrderPreferences(
            hiddenIds = setOf("npo1", "npo2")
        )

        val result = ChannelOrderResolver.apply(backend, prefs)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `backend delivers empty list - returns empty list`() {
        val backend = emptyList<ChannelDto>()
        val prefs = ChannelOrderPreferences(
            orderedIds = listOf("npo1", "npo2")
        )

        val result = ChannelOrderResolver.apply(backend, prefs)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `hidden channel no longer in backend - ignored safely`() {
        val backend = listOf(
            createChannel("npo1"),
            createChannel("npo2")
        )
        val prefs = ChannelOrderPreferences(
            orderedIds = listOf("npo1", "npo2"),
            hiddenIds = setOf("old_hidden")
        )

        val result = ChannelOrderResolver.apply(backend, prefs)

        assertEquals(listOf("npo1", "npo2"), result.map { it.id })
    }

    @Test
    fun `duplicate ids in orderedIds - first occurrence counts, rest ignored`() {
        val backend = listOf(
            createChannel("npo1"),
            createChannel("npo2"),
            createChannel("npo3")
        )
        val prefs = ChannelOrderPreferences(
            orderedIds = listOf("npo3", "npo1", "npo3", "npo2", "npo1")
        )

        val result = ChannelOrderResolver.apply(backend, prefs)

        assertEquals(listOf("npo3", "npo1", "npo2"), result.map { it.id })
    }

    @Test
    fun `id in both orderedIds and hiddenIds - hiddenIds wins`() {
        val backend = listOf(
            createChannel("npo1"),
            createChannel("npo2"),
            createChannel("npo3")
        )
        val prefs = ChannelOrderPreferences(
            orderedIds = listOf("npo3", "npo2", "npo1"),
            hiddenIds = setOf("npo2")
        )

        val result = ChannelOrderResolver.apply(backend, prefs)

        assertEquals(listOf("npo3", "npo1"), result.map { it.id })
    }

    @Test
    fun `orderedIds contains all backend channels and hiddenIds empty - exact permutation`() {
        val backend = listOf(
            createChannel("npo1"),
            createChannel("npo2"),
            createChannel("npo3"),
            createChannel("rtl4")
        )
        val prefs = ChannelOrderPreferences(
            orderedIds = listOf("rtl4", "npo3", "npo1", "npo2")
        )

        val result = ChannelOrderResolver.apply(backend, prefs)

        assertEquals(listOf("rtl4", "npo3", "npo1", "npo2"), result.map { it.id })
        assertEquals(backend.size, result.size)
    }

    @Test
    fun `property test - result always contains exact set of backend ids minus hidden ids`() {
        val backend = listOf(
            createChannel("npo1"),
            createChannel("npo2"),
            createChannel("npo3"),
            createChannel("rtl4"),
            createChannel("rtl5")
        )
        val prefs = ChannelOrderPreferences(
            orderedIds = listOf("rtl4", "npo1", "non_existent"),
            hiddenIds = setOf("npo2", "other_non_existent")
        )

        val result = ChannelOrderResolver.apply(backend, prefs)
        val expectedSet = (backend.map { it.id }.toSet() - prefs.hiddenIds)

        assertEquals(expectedSet, result.map { it.id }.toSet())
    }
}
