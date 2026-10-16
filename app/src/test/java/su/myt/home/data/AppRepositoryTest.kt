package su.myt.home.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import su.myt.home.model.App

class AppRepositoryTest {
    private val context = mock<Context>()
    private val launcherApps = mock<LauncherApps>()
    private val userManager = mock<UserManager>()
    private val packageManager = mock<PackageManager>()
    private val cache = mock<AppCache>()
    private val executor = TestExecutor()
    private val currentUser = mock<UserHandle>()
    private val workUser = mock<UserHandle>()
    private lateinit var process: MockedStatic<Process>
    private lateinit var repository: AppRepository

    @Before
    fun setUp() {
        process = mockStatic(Process::class.java)
        process.`when`<UserHandle> { Process.myUserHandle() }.thenReturn(currentUser)
        whenever(context.getSystemService(LauncherApps::class.java)).thenReturn(launcherApps)
        whenever(context.getSystemService(UserManager::class.java)).thenReturn(userManager)
        whenever(context.packageManager).thenReturn(packageManager)
        whenever(context.packageName).thenReturn("su.myt.home")
        whenever(userManager.userProfiles).thenReturn(listOf(currentUser, workUser))
        whenever(userManager.getSerialNumberForUser(currentUser)).thenReturn(8L)
        whenever(userManager.getSerialNumberForUser(workUser)).thenReturn(12L)
        whenever(packageManager.getUserBadgedLabel(any(), any())).thenAnswer { it.arguments[0] }
        repository = AppRepository(context, executor, cache)
    }

    @After
    fun tearDown() { process.close() }

    @Test
    fun unavailableWorkProfileDoesNotDiscardLoadedPersonalApps() {
        val app = appForTest(currentUser, 8L)
        val activity = activity(app)
        whenever(launcherApps.getActivityList(null, currentUser)).thenReturn(listOf(activity))
        whenever(launcherApps.getActivityList(null, workUser)).thenThrow(SecurityException())

        var result: List<App>? = null
        repository.loadApps { result = it }

        assertEquals(listOf(app.id), result!!.map { it.id })
        assertEquals(null, result!!.single().icon)
        verify(packageManager, never()).queryIntentActivities(any<Intent>(), eq(0))
    }

    @Test
    fun packageManagerFallbackKeepsCurrentUserSerial() {
        whenever(launcherApps.getActivityList(null, currentUser)).thenThrow(SecurityException())
        whenever(launcherApps.getActivityList(null, workUser)).thenReturn(emptyList())
        val info = mock<ResolveInfo>()
        info.activityInfo = ActivityInfo().apply {
            packageName = "com.example"
            name = "com.example.Main"
        }
        whenever(info.loadLabel(packageManager)).thenReturn("Example")
        whenever(packageManager.queryIntentActivities(any<Intent>(), eq(0))).thenReturn(listOf(info))
        mockConstruction(Intent::class.java, { intent, _ ->
            whenever(intent.addCategory(any())).thenReturn(intent)
        }).use {
            mockConstruction(ComponentName::class.java, { component, _ ->
                whenever(component.flattenToString()).thenReturn("com.example/com.example.Main")
            }).use {
                var result: List<App>? = null
                repository.loadApps { result = it }
                assertEquals("8:com.example/com.example.Main", result!!.single().id)
                assertEquals(currentUser, result!!.single().user)
            }
        }
    }

    @Test
    fun profileWithoutSerialIsNotAliasedToPersonalProfile() {
        whenever(userManager.getSerialNumberForUser(workUser)).thenReturn(-1L)
        whenever(launcherApps.getActivityList(null, currentUser)).thenReturn(emptyList())
        repository.loadApps { assertEquals(emptyList<App>(), it) }
        verify(launcherApps, never()).getActivityList(null, workUser)
    }

    @Test
    fun emptySuccessfulQueryDoesNotFallBackToPackageManager() {
        whenever(launcherApps.getActivityList(null, currentUser)).thenReturn(emptyList())
        whenever(launcherApps.getActivityList(null, workUser)).thenReturn(emptyList())
        repository.loadApps { assertEquals(emptyList<App>(), it) }
        verify(cache).saveApps(emptyList())
        verify(packageManager, never()).queryIntentActivities(any<Intent>(), eq(0))
    }

    @Test
    fun shutdownDropsQueuedRefreshAndIgnoresLaterLoads() {
        executor.immediate = false
        var callbacks = 0
        repository.loadApps { callbacks++ }
        val queuedTask = executor.tasks.single()
        repository.shutdown()
        queuedTask.run()
        repository.loadApps { callbacks++ }
        assertEquals(0, callbacks)
        verify(cache, never()).saveApps(any())
    }

    @Test
    fun repeatedObservationDoesNotRegisterDuplicateCallbacks() {
        repository.startObserving {}
        repository.startObserving {}
        repository.stopObserving()
        repository.stopObserving()
        verify(launcherApps).registerCallback(any())
        verify(launcherApps).unregisterCallback(any())
    }

    @Test
    fun loadingMetadataDoesNotDecodeAnyIcons() {
        val activity = activity(appForTest(currentUser, 8L))
        whenever(launcherApps.getActivityList(null, currentUser)).thenReturn(listOf(activity))
        whenever(launcherApps.getActivityList(null, workUser)).thenReturn(emptyList())
        repository.loadApps {}
        verify(activity, never()).getBadgedIcon(any())
    }

    @Test
    fun brokenLabelDoesNotAbortEntireRefresh() {
        val activity = activity(appForTest(currentUser, 8L))
        whenever(activity.label).thenThrow(IllegalStateException("Package removed while loading label"))
        whenever(launcherApps.getActivityList(null, currentUser)).thenReturn(listOf(activity))
        whenever(launcherApps.getActivityList(null, workUser)).thenReturn(emptyList())
        var result: List<App>? = null
        repository.loadApps { result = it }
        assertEquals("com.example", result!!.single().label)
    }

    private fun activity(app: App): LauncherActivityInfo = mock<LauncherActivityInfo>().also {
        whenever(it.componentName).thenReturn(app.component)
        whenever(it.label).thenReturn(app.label)
    }
}
