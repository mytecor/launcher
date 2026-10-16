package su.myt.home.data

import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.AtomicFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.MockedConstruction
import org.mockito.MockedStatic
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.io.FileOutputStream

class AppCacheTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val context = mock<Context>()
    private val iconCache = mock<IconCache>()
    private val userManager = mock<UserManager>()
    private val currentUser = mock<UserHandle>()
    private val component = mock<ComponentName>()
    private lateinit var directory: File
    private lateinit var atomicFiles: MockedConstruction<AtomicFile>
    private lateinit var process: MockedStatic<Process>
    private lateinit var components: MockedStatic<ComponentName>
    private var recoveredData: ByteArray? = null

    @Before
    fun setUp() {
        directory = temporaryFolder.newFolder()
        whenever(context.getSystemService(UserManager::class.java)).thenReturn(userManager)
        whenever(component.flattenToString()).thenReturn("com.example/com.example.Main")
        whenever(iconCache.getIconFileName(any())).thenReturn("icon.png")
        process = mockStatic(Process::class.java)
        process.`when`<UserHandle> { Process.myUserHandle() }.thenReturn(currentUser)
        components = mockStatic(ComponentName::class.java)
        components.`when`<ComponentName> { ComponentName.unflattenFromString("com.example/com.example.Main") }
            .thenReturn(component)
        atomicFiles = mockConstruction(AtomicFile::class.java, { atomicFile, construction ->
            val file = construction.arguments()[0] as File
            whenever(atomicFile.readFully()).thenAnswer { recoveredData ?: file.readBytes() }
            whenever(atomicFile.startWrite()).thenAnswer { FileOutputStream(file) }
            doAnswer { call ->
                call.getArgument<FileOutputStream>(0).close()
                null
            }.whenever(atomicFile).finishWrite(any())
        })
    }

    @After
    fun tearDown() {
        atomicFiles.close()
        components.close()
        process.close()
    }

    @Test
    fun savingEmptySnapshotReplacesOldAppsInMemoryAndOnDisk() {
        val cache = AppCache(context, directory, iconCache)
        cache.saveApps(listOf(appForTest(currentUser)))
        cache.saveApps(emptyList())
        assertTrue(cache.loadApps().isEmpty())
        assertEquals("[]", File(directory, "apps.json").readText())
        assertTrue(AppCache(context, directory, iconCache).loadApps().isEmpty())
    }

    @Test
    fun cachedSnapshotDoesNotRetainCallersMutableList() {
        val apps = mutableListOf(appForTest(currentUser))
        val cache = AppCache(context, directory, iconCache)
        cache.saveApps(apps)
        apps.clear()
        assertEquals(1, cache.loadApps().size)
    }

    @Test
    fun missingProfileIsNotReboundToCurrentUser() {
        writeSnapshot(serial = 12L)
        assertTrue(AppCache(context, directory, iconCache).loadApps().isEmpty())
    }

    @Test
    fun resolvedProfileKeepsItsIdentity() {
        val workUser = mock<UserHandle>()
        whenever(userManager.getUserForSerialNumber(12L)).thenReturn(workUser)
        writeSnapshot(serial = 12L)
        val app = AppCache(context, directory, iconCache).loadApps().single()
        assertEquals(workUser, app.user)
        assertEquals("12:com.example/com.example.Main", app.id)
    }

    @Test
    fun atomicFileCanRecoverBackupWhenBaseFileIsMissing() {
        recoveredData = snapshot(0L).toByteArray()
        assertFalse(File(directory, "apps.json").exists())
        assertEquals(1, AppCache(context, directory, iconCache).loadApps().size)
    }

    private fun writeSnapshot(serial: Long) {
        File(directory, "apps.json").writeText(snapshot(serial))
    }

    private fun snapshot(serial: Long) =
        """[{"label":"Example","component":"com.example/com.example.Main","userSerial":$serial}]"""
}
