import javafx.beans.binding.*
import javafx.beans.property.*
import javafx.collections.*
import javafx.geometry.*
import javafx.scene.control.*
import javafx.scene.control.TableView.*
import javafx.scene.layout.*
import javafx.scene.text.*
import org.adoptopenjdk.jitwatch.core.*
import org.adoptopenjdk.jitwatch.model.*
import org.adoptopenjdk.jitwatch.parser.hotspot.*
import tornadofx.*
import java.io.*
import java.lang.Integer.*


fun main(args: Array<String>) {
    launch<Profiler>(args)
}

class Profiler : App(ProfilerView::class)

class ProfilerView : View("Profiler") {
    private val selectedProcessInfo = SimpleObjectProperty<File>()
    private val values = FXCollections.observableArrayList<JitProfilingInfo>()
    private val selectedMethod = SimpleObjectProperty<JitProfilingInfo>()
    private val profileCompleted = SimpleObjectProperty<Boolean>()
    private lateinit var table: TableView<JitProfilingInfo>

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
                    profileCompleted.set(true)
                }
            }
            val classToSearch = textfield {
                promptText = "Пока не работает"
                enableWhen { profileCompleted.isNotNull }
                prefWidth = 400.0
//                todo: делать автоматический поиск по всем методам и показывать в dropdown menu
            }
        }
        table = tableview(values) {
            column("Method name", JitProfilingInfo::methodNameProperty)
            column("Total compilation time (ms)", JitProfilingInfo::totalCompilationTimeProperty)
            column("Decompilation count", JitProfilingInfo::decompilationCountProperty)
            column("Current native size", JitProfilingInfo::currentNativeSizeProperty)
            bindSelected(selectedMethod)
            setPrefSize(667.0 * 2, 376.0 * 2)
            columnResizePolicy = UNCONSTRAINED_RESIZE_POLICY
            vgrow = Priority.ALWAYS

            contextMenu = ContextMenu().apply {
                item("Show detailed info").action {
                    selectedItem?.let {
                        DetailedMethodInfoView(it).openWindow()
                    }
                }
            }

            autoResizeColumn()
        }
    }

    private fun TableView<JitProfilingInfo>.autoResizeColumn() {
        // todo: разобраться с изменением ширины колонок
        items.onChange {
            val scrollbarWidth = this.lookupAll(".scroll-bar")
                    .map { it as ScrollBar }
                    .firstOrNull { bar -> bar.orientation == Orientation.VERTICAL }?.widthProperty()
                    ?: SimpleDoubleProperty()
            val usedWidth: DoubleBinding = columns[1].widthProperty()
                    .add(columns[2].widthProperty()).add(columns[3].widthProperty()).add(scrollbarWidth)
            // todo: исправить, чтобы -3 было только в случае, если есть bar
            columns[0].prefWidthProperty().bind(this.widthProperty().subtract(usedWidth).subtract(SimpleDoubleProperty(3.0)))
        }
        columns[1].prefWidth = Text(columns[1].text).layoutBounds.width + 30.0
        columns[2].prefWidth = Text(columns[2].text).layoutBounds.width + 30.0
        columns[3].prefWidth = Text(columns[3].text).layoutBounds.width + 30.0
        columns[1].isResizable = false
        columns[2].isResizable = false
        columns[3].isResizable = false
        val usedWidth: DoubleBinding = columns[1].widthProperty().add(columns[2].widthProperty()).add(columns[3].widthProperty())
        columns[0].prefWidthProperty().bind(this.widthProperty().subtract(usedWidth))
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
        if (events.isEmpty()) {
            list += JitProfilingInfo(name, -1, -1, -1, -1, events, model)
            continue
        }
        val time = events[0].eventMember.compilations.map { it.compilationDuration }.sum()
        val nativeSize = events[0].eventMember.lastCompilation?.nativeSize ?: -1
        val bytecodeSize = events[0].eventMember.lastCompilation?.bytecodeSize ?: -1
        val decompilationCount = if (events[0].eventMember.lastCompilation != null) {
            events[0].eventMember.lastCompilation.compiledAttributes["decompiles"]?.toInt() ?: 0
        } else 0
        list += JitProfilingInfo(name, time, decompilationCount, nativeSize, bytecodeSize, events, model)
    }
    return list
}

data class JitProfilingInfo(val methodName: String,
                            val totalCompilationTime: Long,
                            val decompilationCount: Int,
                            val currentNativeSize: Int,
                            val currentBytecodeSize: Int,
                            val events: List<JITEvent>,
                            val model: JITDataModel) {
    val methodNameProperty = SimpleStringProperty(methodName)
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