import javafx.beans.property.*
import javafx.collections.*
import javafx.scene.control.TableView.*
import javafx.scene.layout.*
import org.adoptopenjdk.jitwatch.core.*
import org.adoptopenjdk.jitwatch.model.*
import org.adoptopenjdk.jitwatch.parser.hotspot.*
import tornadofx.*
import java.io.*

fun main(args: Array<String>) {
    launch<Profiler>(args)
}

class Profiler : App(ProfilerView::class)

class ProfilerView : View("Profiler") {
    private val selectedProcessInfo = SimpleObjectProperty<File>()
    private val values = FXCollections.observableArrayList<JitProfilingInfo>()
    private val selectedMethod = SimpleObjectProperty<JitProfilingInfo>()

    override val root = vbox {
        hbox {
            button("Choose file to profile") {
                action {
                    val file = chooseFile("Select file", arrayOf())[0]
                    selectedProcessInfo.set(file)
                }
            }
            button("Start profiling") {
                enableWhen { selectedProcessInfo.isNotNull }
                action {
                    val profile = profile(selectedProcessInfo.get().absolutePath)
                    values.setAll(profile)
                }
            }
        }
        tableview(values) {
            column("Method name", JitProfilingInfo::functionNameProperty)
            column("Total compilation time (ms)", JitProfilingInfo::totalCompilationTimeProperty)
            column("Decompilation count", JitProfilingInfo::decompilationCountProperty)
            column("Current native size", JitProfilingInfo::currentNativeSizeProperty)
            bindSelected(selectedMethod)
            setPrefSize(667.0 * 2, 376.0 * 2)
            columnResizePolicy = CONSTRAINED_RESIZE_POLICY
            vgrow = Priority.ALWAYS
        }
    }
}

private fun profile(jitLogFile: String): List<JitProfilingInfo> {
    val model = parseLogFile(jitLogFile)
    val eventListCopy = model.eventListCopy
    val map = HashMap<String, MutableList<JITEvent>>()
    for (event in eventListCopy) {
        map.getOrPut(event.eventMember.fullyQualifiedMemberName) { ArrayList() }.add(event)
    }
    return buildTableInfo(map, model)
}

private fun parseLogFile(jitLogFile: String): JITDataModel {
    val logParser = HSLPWrapper(JitListenerNoOp())
    logParser.reset()
    logParser.configureDisposableClassLoaderOpen()
    logParser.splitLogFileOpen(File(jitLogFile))
    logParser.parseLogFileOpen()
    return logParser.model ?: kotlin.error("Couldn't build Jit data model")
}

private fun buildTableInfo(map: HashMap<String, MutableList<JITEvent>>, model: JITDataModel): ArrayList<JitProfilingInfo> {
    val list = ArrayList<JitProfilingInfo>()
    for (name in map.keys) {
        val events = map[name]!!
        val time = events[0].eventMember.compilations.map { it.compilationDuration }.sum()
        val nativeSize = events[0].eventMember.lastCompilation?.nativeSize ?: -1
        val decompilationCount = if (events[0].eventMember.lastCompilation != null) {
            events[0].eventMember.lastCompilation.compiledAttributes["decompiles"]?.toInt() ?: 0
        } else 0
        list += JitProfilingInfo(name, time, decompilationCount, nativeSize, events, model)
    }
    return list
}

data class JitProfilingInfo(val functionName: String,
                            val totalCompilationTime: Long,
                            val decompilationCount: Int,
                            val currentNativeSize: Int,
                            val events: List<JITEvent>,
                            val model: JITDataModel) {
    val functionNameProperty = SimpleStringProperty(functionName)
    val totalCompilationTimeProperty = SimpleLongProperty(totalCompilationTime)
    val decompilationCountProperty = SimpleIntegerProperty(decompilationCount)
    val currentNativeSizeProperty = SimpleIntegerProperty(currentNativeSize)
}

private class HSLPWrapper(jitListener: IJITListener?) : HotSpotLogParser(jitListener) {
    fun splitLogFileOpen(file: File) = splitLogFile(file)

    fun parseLogFileOpen() = parseLogFile()

    fun configureDisposableClassLoaderOpen() = configureDisposableClassLoader()
}

private class JitListenerNoOp : IJITListener {
    override fun handleLogEntry(entry: String?) {
    }

    override fun handleErrorEntry(entry: String?) {
    }

    override fun handleReadComplete() {
    }

    override fun handleJITEvent(event: JITEvent?) {
    }

    override fun handleReadStart() {
    }
}