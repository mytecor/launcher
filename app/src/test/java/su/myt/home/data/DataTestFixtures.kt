package su.myt.home.data

import android.content.ComponentName
import android.os.UserHandle
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import su.myt.home.model.App
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

internal class TestExecutor(var immediate: Boolean = true) : AbstractExecutorService() {
    val tasks = mutableListOf<Runnable>()
    private var stopped = false

    override fun execute(command: Runnable) {
        if (stopped) throw RejectedExecutionException()
        if (immediate) command.run() else tasks.add(command)
    }

    override fun shutdown() { stopped = true }
    override fun shutdownNow(): MutableList<Runnable> {
        stopped = true
        return tasks.toMutableList().also { tasks.clear() }
    }
    override fun isShutdown() = stopped
    override fun isTerminated() = stopped && tasks.isEmpty()
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
}

internal fun appForTest(user: UserHandle, serial: Long = 0L, pkg: String = "com.example"): App {
    val component = mock<ComponentName>()
    whenever(component.packageName).thenReturn(pkg)
    whenever(component.flattenToString()).thenReturn("$pkg/$pkg.Main")
    return App("Example", "Example", component, user, serial)
}
