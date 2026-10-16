package su.myt.home.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AppLauncherTest {
    private val context = mock<Context>()
    private val currentUser = mock<UserHandle>()

    @Test
    fun missingLauncherServiceUsesCurrentProfileIntent() {
        mockStatic(Process::class.java).use { process ->
            process.`when`<UserHandle> { Process.myUserHandle() }.thenReturn(currentUser)
            mockConstruction(Intent::class.java, { intent, _ ->
                whenever(intent.addCategory(any())).thenReturn(intent)
                whenever(intent.setComponent(any())).thenReturn(intent)
                whenever(intent.addFlags(any())).thenReturn(intent)
            }).use {
                assertEquals(LaunchResult.Success, AppLauncher(context).launch(appForTest(currentUser)))
                verify(context).startActivity(any<Intent>())
            }
        }
    }

    @Test
    fun deniedWorkProfileNeverLaunchesPersonalCopy() {
        val launcherApps = mock<LauncherApps>()
        val workUser = mock<UserHandle>()
        val app = appForTest(workUser, 12L)
        whenever(context.getSystemService(LauncherApps::class.java)).thenReturn(launcherApps)
        doThrow(SecurityException()).whenever(launcherApps).startMainActivity(app.component, workUser, null, null)
        mockStatic(Process::class.java).use { process ->
            process.`when`<UserHandle> { Process.myUserHandle() }.thenReturn(currentUser)
            assertEquals(LaunchResult.Failed, AppLauncher(context).launch(app))
            verify(context, never()).startActivity(any<Intent>())
        }
    }

    @Test
    fun missingServiceDoesNotReportSuccessForWorkProfile() {
        mockStatic(Process::class.java).use { process ->
            process.`when`<UserHandle> { Process.myUserHandle() }.thenReturn(currentUser)
            assertEquals(LaunchResult.Failed, AppLauncher(context).launch(appForTest(mock(), 12L)))
            verify(context, never()).startActivity(any<Intent>())
        }
    }

    @Test
    fun missingActivityReportsNotFound() {
        val launcherApps = mock<LauncherApps>()
        val app = appForTest(currentUser)
        whenever(context.getSystemService(LauncherApps::class.java)).thenReturn(launcherApps)
        doThrow(ActivityNotFoundException()).whenever(launcherApps).startMainActivity(app.component, currentUser, null, null)
        assertEquals(LaunchResult.NotFound, AppLauncher(context).launch(app))
    }
}
