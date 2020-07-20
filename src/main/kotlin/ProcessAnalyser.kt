@file:Suppress("JAVA_MODULE_DOES_NOT_EXPORT_PACKAGE")

import javafx.beans.property.SimpleObjectProperty
import javafx.collections.FXCollections
import javafx.scene.control.Alert
import sun.jvmstat.monitor.MonitoredHost
import sun.jvmstat.monitor.MonitoredVm
import sun.jvmstat.monitor.MonitoredVmUtil
import sun.jvmstat.monitor.VmIdentifier
import tornadofx.*
import java.util.*
import kotlin.error


/**
 * Сканирует все java-процессы, возвращает все, кроме процесса профайлера
 */
private fun scanProcesses(): List<ProcessInfo> {
    val local = MonitoredHost.getMonitoredHost("localhost")
    val processInfos = mutableListOf<ProcessInfo>()
    for (pid in local.activeVms().map { it.toLong() }) {
        if (pid == ProcessHandle.current().pid()) continue
        val id = VmIdentifier("//$pid?mode=r")
        val vm: MonitoredVm = local.getMonitoredVm(id, 0)
        processInfos += if (MonitoredVmUtil.mainClass(vm, true).isEmpty()) {
            ProcessInfo(pid, "UnknownMainClass", MonitoredVmUtil.mainArgs(vm) ?: "")
        } else {
            ProcessInfo(pid, MonitoredVmUtil.mainClass(vm, true), MonitoredVmUtil.mainArgs(vm) ?: "")
        }
    }
    return processInfos
}

/**
 * Получает время, когда был запущен процесс
 */
private fun getProcessStartTime(pid: Long): Long {
    val processHandle = ProcessHandle.of(pid).orElse(null) ?: error("Unknown process id")
    val instant = processHandle.info().startInstant().orElse(null) ?: error("Can't get process start time")
    return instant.toEpochMilli()
}

class ProcessAnalyserView : View("Profiler") {
    private val controller: MyController by inject()
    private val selectedProcessInfo = SimpleObjectProperty<ProcessInfo>()
    override val root = vbox {
        listview(controller.values) {
            bindSelected(selectedProcessInfo)
        }
        button("Get start time") {
            enableWhen { selectedProcessInfo.isNotNull }
            action {
                val obj = selectedProcessInfo.value
                alert(Alert.AlertType.INFORMATION,
                        "Process start time",
                        Date(getProcessStartTime(obj.pid)).toString())
            }
        }
    }
}

class MyController : Controller() {
    val values = FXCollections.observableArrayList(scanProcesses())
    // todo: тред, который переодически перезапускает scan process
}

data class ProcessInfo(val pid: Long, val mainClass: String, val mainArgs: String) {
    override fun toString(): String {
        return "$mainClass ($pid)"
    }
}

class Client : App(ProcessAnalyserView::class)

fun main(args: Array<String>) {
    launch<Client>(args)
}
