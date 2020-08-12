@file:Suppress("JAVA_MODULE_DOES_NOT_EXPORT_PACKAGE")

import com.google.common.collect.*
import com.sun.tools.attach.*
import javafx.beans.property.*
import javafx.collections.*
import javafx.concurrent.*
import javafx.geometry.*
import javafx.scene.*
import javafx.scene.control.*
import javafx.scene.layout.*
import sun.jvmstat.monitor.*
import tornadofx.*
import java.io.*
import java.util.*
import java.util.concurrent.*
import kotlin.collections.HashSet
import kotlin.error

class ProcessAnalyserView(private val informationExtractor: JitProfilingInformationExtractor) : Parent() {
    private val javaProcesses = FXCollections.observableArrayList(scanProcesses())
    private val processInfoToProfile = SimpleObjectProperty<ProcessInfo>()
    private val selectedProcessActive = SimpleObjectProperty<Boolean>()
    private val executor: ScheduledExecutorService
    private val selectedProcessInfo = SimpleObjectProperty<ProcessInfo>()

    init {
        val factory: (Runnable?) -> Thread = { r: Runnable? ->
            val t = Thread(r)
            t.isDaemon = true
            t
        }
        executor = Executors.newSingleThreadScheduledExecutor(factory)
        executor.scheduleAtFixedRate({ getGetJavaProcessTask().run() }, 100, 100, TimeUnit.MILLISECONDS)
        executor.scheduleAtFixedRate({ getProcessActiveTask().run() }, 100, 100, TimeUnit.MILLISECONDS)
    }

    val root = vbox {
        label {
            text = "Choose java process to profile"
        }
        listview(javaProcesses) {
            setPrefSize(667.0, 376.0)
            vgrow = Priority.ALWAYS
            bindSelected(selectedProcessInfo)
            contextMenu = ContextMenu().apply {
                item("Select process to profile").action {
                    selectedItem?.let {
                        if (jitLoggingEnabled(it)) {
                            processInfoToProfile.set(it)
                            informationExtractor.clearProfilingInfoInterval()
                        } else {
                            warning("Cannot start profiling because jit logging wasn't enabled")
                        }
                    }
                }
            }
        }
        addProcessingButtons()
        alignment = Pos.CENTER
        hgrow = Priority.ALWAYS
        vgrow = Priority.ALWAYS
    }

    private fun VBox.addProcessingButtons() {
        hbox {
            textfield {
                promptText = "Chosen process"
                isDisable = true
                text = ""
                prefWidth = 400.0
                processInfoToProfile.addListener { _, _, chosenProcess ->
                    text = chosenProcess.toString()
                }
            }
            button("Start") {
                enableWhen { processInfoToProfile.isNotNull.and(selectedProcessActive.isEqualTo(true)) }
                action {
                    informationExtractor.setProfilingInfoFrom(processInfoToProfile.get().startTime, System.currentTimeMillis())
                }
            }
            button("Finish") {
                enableWhen { processInfoToProfile.isNotNull.and(selectedProcessActive.isEqualTo(true)) }
                action {
                    informationExtractor.setProfilingInfoTo(processInfoToProfile.get().startTime, System.currentTimeMillis())
                }
            }
            button("Clear") {
                action {
                    informationExtractor.clearProfilingInfoInterval()
                }
            }
            button("Process log file") {
                enableWhen { selectedProcessActive.isEqualTo(false) }
                action {
                    val logFile = getLogFile(processInfoToProfile.get())
                    if (logFile.isNullOrEmpty() || !File(logFile).exists()) {
                        warning("Cannot find the jit log file, please specify it manually")
                    } else {
                        val file = File(logFile)
                        informationExtractor.startProfiling(file)
                    }
                }
            }
        }
    }

    private fun jitLoggingEnabled(processInfo: ProcessInfo) = processInfo.jvmArgs.contains("-XX:+LogCompilation")

    private fun getLogFile(processInfo: ProcessInfo): String? {
        return processInfo.jvmArgs.split(" ")
                .firstOrNull { it.startsWith("-XX:LogFile") }
                ?.split("=")?.getOrNull(1)
                ?: getDefaultFile(processInfo)
    }

    private fun getDefaultFile(processInfo: ProcessInfo): String? = processInfo.systemProperties?.
                                            getProperty("user.dir")?.plus("/hotspot_pid${processInfo.pid}.log")

    private fun getGetJavaProcessTask(): Task<List<ProcessInfo>> = object : Task<List<ProcessInfo>>() {
        init {
            setOnSucceeded {
                val prevProcesses = HashSet<ProcessInfo>(javaProcesses)
                val newProcesses = HashSet<ProcessInfo>(value)
                javaProcesses.removeAll(Sets.difference(prevProcesses, newProcesses))
                javaProcesses.addAll(Sets.difference(newProcesses, prevProcesses))
            }
        }

        override fun call(): List<ProcessInfo> = scanProcesses()
    }

    private fun getProcessActiveTask(): Task<Boolean> = object : Task<Boolean>() {
        init {
            setOnSucceeded {
                selectedProcessActive.set(value)
            }
        }

        override fun call(): Boolean? {
            if (processInfoToProfile.value != null) {
                return isProcessActive(processInfoToProfile.get().pid)
            }
            return null
        }
    }
}

/**
 * Сканирует все java-процессы, возвращает все, кроме процесса профайлера
 */
private fun scanProcesses(): List<ProcessInfo> {
    val local = MonitoredHost.getMonitoredHost("localhost")
    val processInfos = mutableListOf<ProcessInfo>()
    for (pid in local.activeVms().map { it.toLong() }) {
        try {
            if (pid == ProcessHandle.current().pid()) continue
            val id = VmIdentifier("//$pid?mode=r")
            val vm: MonitoredVm = local.getMonitoredVm(id, 0)
            val systemProperties: Properties? = getSystemPropertiesIfPossible(vm)
            val mainClass = if (MonitoredVmUtil.mainClass(vm, true).isEmpty()) {
                "UnknownMainClass"
            } else {
                MonitoredVmUtil.mainClass(vm, true)
            }
            val startTime = getProcessStartTime(pid)
            if (startTime != null) {
                processInfos += ProcessInfo(pid, mainClass, MonitoredVmUtil.mainArgs(vm) ?: "",
                        MonitoredVmUtil.jvmArgs(vm) ?: "", systemProperties, startTime)
            }
        } catch (ignored: MonitorException) {} // может случиться, если в процессе получения информации процесс остановился
    }
    return processInfos
}

private fun getSystemPropertiesIfPossible(vm: MonitoredVm): Properties? {
    var systemProperties: Properties? = null
    try {
        val attach = VirtualMachine.attach(vm.vmIdentifier.localVmId.toString())
        try {
            systemProperties = attach.systemProperties
        } finally {
            attach.detach()
        }
    } catch (ex: AttachNotSupportedException) {
        print("\t$ex")
    } catch (ex: IOException) {
        print("\t$ex")
    }
    return systemProperties
}

private fun isProcessActive(pid: Long): Boolean {
    val processHandle = ProcessHandle.of(pid)
    return processHandle.isPresent && processHandle.get().isAlive
}

/**
 * Получает время, когда был запущен процесс
 */
private fun getProcessStartTime(pid: Long): Long? {
    val processHandle = ProcessHandle.of(pid).orElse(null) ?: return null
    val instant = processHandle.info().startInstant().orElse(null) ?: error("Can't get process start time")
    return instant.toEpochMilli()
}

data class ProcessInfo(val pid: Long, val mainClass: String, val mainArgs: String, val jvmArgs: String,
                       val systemProperties: Properties?, val startTime: Long) {
    override fun toString(): String {
        return "$mainClass ($pid)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ProcessInfo
        if (pid != other.pid) return false
        return true
    }

    override fun hashCode(): Int {
        return pid.hashCode()
    }
}