package su.myt.home.data

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.UserHandle
import android.util.LruCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

class IconCacheTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val context = mock<Context>()
    private val executor = TestExecutor()
    private val memoryCache = mock<LruCache<String, Drawable>>()
    private val memory = mutableMapOf<String, Drawable>()

    @Before
    fun setUp() {
        whenever(memoryCache.get(any())).thenAnswer { memory[it.arguments[0]] }
        whenever(memoryCache.put(any(), any())).thenAnswer {
            memory.put(it.getArgument(0), it.getArgument(1))
        }
        whenever(memoryCache.remove(any())).thenAnswer { memory.remove(it.arguments[0]) }
        whenever(memoryCache.snapshot()).thenAnswer { memory.toMap() }
    }

    @Test
    fun fallbackRequestsBadgedIconFromAppsOwnProfile() {
        val user = mock<UserHandle>()
        val app = appForTest(user, 12L)
        val launcherApps = mock<LauncherApps>()
        val activity = mock<LauncherActivityInfo>()
        whenever(activity.componentName).thenReturn(app.component)
        whenever(context.getSystemService(LauncherApps::class.java)).thenReturn(launcherApps)
        whenever(launcherApps.getActivityList("com.example", user)).thenReturn(listOf(activity))
        val cache = createCache()

        cache.loadIcon(app) {}

        verify(launcherApps).getActivityList("com.example", user)
        verify(activity).getBadgedIcon(0)
    }

    @Test
    fun simultaneousRequestsShareOneDiskTask() {
        executor.immediate = false
        val app = appForTest(mock())
        val cache = createCache()
        cache.loadIcon(app) {}
        cache.loadIcon(app) {}
        assertEquals(1, executor.tasks.size)
    }

    @Test
    fun shutdownCancelsQueuedRequestsAndRejectsFurtherWork() {
        executor.immediate = false
        val app = appForTest(mock())
        val cache = createCache()
        cache.loadIcon(app) {}
        cache.shutdown()
        cache.loadIcon(app) {}
        assertEquals(0, executor.tasks.size)
    }

    @Test
    fun classNameCharactersDoNotCollideInDiskCache() {
        val first = appForTest(mock())
        val second = appForTest(mock())
        whenever(first.component.flattenToString()).thenReturn("com.example/com.example.Main\$Alias")
        whenever(second.component.flattenToString()).thenReturn("com.example/com.example.Main_Alias")
        val cache = createCache()
        assertNotEquals(cache.getIconFileName(first), cache.getIconFileName(second))
    }

    @Test
    fun invalidationClearsMemoryAndDiskWithoutTouchingNeighboringPackage() {
        val directory = temporaryFolder.newFolder()
        val cache = createCache(directory)
        val app = appForTest(mock(), pkg = "com.example")
        val neighboringApp = appForTest(mock(), pkg = "com.example_extra")
        memory[app.id] = mock()
        memory[neighboringApp.id] = mock()
        val file = File(directory, cache.getIconFileName(app)).apply { writeText("old") }
        val neighboringFile = File(directory, cache.getIconFileName(neighboringApp)).apply { writeText("keep") }

        cache.invalidatePackage("com.example")

        assertFalse(memory.containsKey(app.id))
        assertTrue(memory.containsKey(neighboringApp.id))
        assertFalse(file.exists())
        assertTrue(neighboringFile.exists())
    }

    @Test
    fun invalidatedPendingLoadCannotRepopulateMemoryOrNotifyListeners() {
        executor.immediate = false
        val app = appForTest(mock(), 12L)
        val launcherApps = mock<LauncherApps>()
        val activity = mock<LauncherActivityInfo>()
        val icon = mock<Drawable>()
        whenever(activity.componentName).thenReturn(app.component)
        whenever(activity.getBadgedIcon(0)).thenReturn(icon)
        whenever(context.getSystemService(LauncherApps::class.java)).thenReturn(launcherApps)
        whenever(launcherApps.getActivityList("com.example", app.user)).thenReturn(listOf(activity))
        val cache = createCache()
        var callbacks = 0
        cache.loadIcon(app) { callbacks++ }
        cache.invalidatePackage("com.example")
        executor.tasks.single().run()

        verify(memoryCache, never()).put(any(), any())
        assertEquals(0, callbacks)
    }

    @Test
    fun invalidatedIconIsLoadedAgainAndDeliveredToEveryWaitingView() {
        val app = appForTest(mock(), 12L)
        val oldIcon = mock<Drawable>()
        val newIcon = mock<Drawable>()
        val launcherApps = mock<LauncherApps>()
        val activity = mock<LauncherActivityInfo>()
        whenever(activity.componentName).thenReturn(app.component)
        whenever(activity.getBadgedIcon(0)).thenReturn(newIcon)
        whenever(context.getSystemService(LauncherApps::class.java)).thenReturn(launcherApps)
        whenever(launcherApps.getActivityList("com.example", app.user)).thenReturn(listOf(activity))
        mockConstruction(Handler::class.java, { handler, _ ->
            doAnswer { call ->
                call.getArgument<Runnable>(0).run()
                true
            }.whenever(handler).post(any())
        }).use {
            val cache = createCache()
            memory[app.id] = oldIcon
            assertEquals(oldIcon, cache.loadIcon(app) {})
            cache.invalidatePackage("com.example")
            executor.immediate = false
            val received = mutableListOf<Drawable>()
            cache.loadIcon(app) { icon -> received.add(icon) }
            cache.loadIcon(app) { icon -> received.add(icon) }
            executor.tasks.single().run()
            assertEquals(listOf(newIcon, newIcon), received)
            assertEquals(newIcon, memory[app.id])
        }
    }

    private fun createCache(directory: File = temporaryFolder.newFolder()) =
        IconCache(context, directory, executor, memoryCache)
}
