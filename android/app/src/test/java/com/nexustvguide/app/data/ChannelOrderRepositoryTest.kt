package com.nexustvguide.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nexustvguide.app.data.model.ChannelOrderPreferences
import com.nexustvguide.app.data.repository.ChannelOrderRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChannelOrderRepositoryTest {

    private lateinit var context: Context
    private lateinit var repository: ChannelOrderRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("channel_order", Context.MODE_PRIVATE).edit().clear().commit()
        repository = ChannelOrderRepository(context)
    }

    @Test
    fun `load on empty prefs returns default preferences`() {
        val prefs = repository.load()
        assertEquals(1, prefs.version)
        assertTrue(prefs.orderedIds.isEmpty())
        assertTrue(prefs.hiddenIds.isEmpty())
    }

    @Test
    fun `save and load persists and retrieves preferences`() {
        val original = ChannelOrderPreferences(
            version = 1,
            orderedIds = listOf("npo1", "npo2", "rtl4"),
            hiddenIds = setOf("sbs6")
        )
        repository.save(original)

        val loaded = repository.load()
        assertEquals(original.version, loaded.version)
        assertEquals(original.orderedIds, loaded.orderedIds)
        assertEquals(original.hiddenIds, loaded.hiddenIds)
    }

    @Test
    fun `reset clears saved preferences`() {
        val original = ChannelOrderPreferences(
            version = 1,
            orderedIds = listOf("npo1", "npo2"),
            hiddenIds = setOf("rtl4")
        )
        repository.save(original)
        repository.reset()

        val loaded = repository.load()
        assertTrue(loaded.orderedIds.isEmpty())
        assertTrue(loaded.hiddenIds.isEmpty())
    }

    @Test
    fun `load handles corrupted JSON safely`() {
        val sharedPrefs = context.getSharedPreferences("channel_order", Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("prefs_json", "{ broken json").commit()

        val loaded = repository.load()
        assertEquals(1, loaded.version)
        assertTrue(loaded.orderedIds.isEmpty())
        assertTrue(loaded.hiddenIds.isEmpty())
    }

    @Test
    fun `load handles null JSON value safely`() {
        val sharedPrefs = context.getSharedPreferences("channel_order", Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("prefs_json", "null").commit()

        val loaded = repository.load()
        assertEquals(1, loaded.version)
        assertTrue(loaded.orderedIds.isEmpty())
        assertTrue(loaded.hiddenIds.isEmpty())
    }

    @Test
    fun `load handles unknown version by falling back to defaults`() {
        val sharedPrefs = context.getSharedPreferences("channel_order", Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("prefs_json", "{\"version\":99,\"orderedIds\":[\"npo1\"]}").commit()

        val loaded = repository.load()
        assertEquals(1, loaded.version)
        assertTrue(loaded.orderedIds.isEmpty())
    }

    @Test
    fun `load sanitizes missing collections to empty`() {
        val sharedPrefs = context.getSharedPreferences("channel_order", Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("prefs_json", "{\"version\":1}").commit()

        val loaded = repository.load()
        assertEquals(1, loaded.version)
        assertTrue(loaded.orderedIds.isEmpty())
        assertTrue(loaded.hiddenIds.isEmpty())
    }

    @Test
    fun `observe emits initial value and subsequent updates`() = runTest(UnconfinedTestDispatcher()) {
        val emitted = mutableListOf<ChannelOrderPreferences>()
        val job = launch {
            repository.observe().collect {
                emitted.add(it)
            }
        }

        // Initial emission
        assertEquals(1, emitted.size)
        assertTrue(emitted[0].orderedIds.isEmpty())

        // First change
        val update1 = ChannelOrderPreferences(orderedIds = listOf("npo1"))
        repository.save(update1)
        assertEquals(2, emitted.size)
        assertEquals(listOf("npo1"), emitted[1].orderedIds)

        // Second change
        val update2 = ChannelOrderPreferences(orderedIds = listOf("npo2"), hiddenIds = setOf("npo1"))
        repository.save(update2)
        assertEquals(3, emitted.size)
        assertEquals(listOf("npo2"), emitted[2].orderedIds)
        assertEquals(setOf("npo1"), emitted[2].hiddenIds)

        job.cancel()
    }
}
