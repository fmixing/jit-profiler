import javafx.beans.binding.*
import javafx.beans.property.*
import javafx.collections.*
import javafx.concurrent.Task
import javafx.geometry.*
import javafx.scene.control.*
import javafx.scene.control.TableView.*
import javafx.scene.layout.*
import javafx.scene.text.*
import org.adoptopenjdk.jitwatch.core.*
import org.adoptopenjdk.jitwatch.model.*
import org.adoptopenjdk.jitwatch.parser.hotspot.*
import org.adoptopenjdk.jitwatch.util.*
import tornadofx.*
import java.io.*
import java.util.concurrent.Executors.*
import kotlin.math.*


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
    private lateinit var profilingIndicator: ProgressIndicator
    private val executor = newSingleThreadExecutor()

    override val root = vbox {
        hbox {
            button("Choose file to profile") {
                action {
                    val file = chooseFile("Select file", arrayOf())
                    if (file.isNotEmpty()) selectedProcessInfo.set(file[0])
                }
            }
            button("Start profiling") {
                enableWhen { selectedProcessInfo.isNotNull }
                action {
                    values.clear()
                    profilingIndicator.isVisible = true
                    profileCompleted.set(null)
                    loadJitProfilingInfo()
                }
            }
            profilingIndicator = progressindicator {
                isVisible = false
            }
            textfield {
                promptText = "Enter class name"
                enableWhen { profileCompleted.isNotNull }
                prefWidth = 400.0
                textProperty().addListener { obs, old, new ->
                    val filter = values.filter { it.fullMethodName.startsWith(new) || it.fullMethodName.contains(new) }
                    this.contextMenu = ContextMenu().apply {
                        repeat(min(filter.size, 10)) {
                            item(filter[it].fullMethodName).action {
                                table.scrollTo(filter[it])
                                table.selectionModel.select(filter[it])
                            }
                        }
                        show(this@textfield, Side.BOTTOM, 10.0, -5.0)
                    }
                }
            }
        }
        table = createJitProfilingInfoTable()
        add(table)
    }

    private fun loadJitProfilingInfo() {
        val listLoader = object : Task<List<JitProfilingInfo>>() {
            init {
                setOnSucceeded {
                    values.setAll(value)
                    profilingIndicator.isVisible = false
                    profileCompleted.set(true)
                }
            }

            override fun call(): List<JitProfilingInfo> = profile(selectedProcessInfo.get().absolutePath)
        }
        executor.submit(listLoader)
    }

    private fun createJitProfilingInfoTable(): TableView<JitProfilingInfo> {
        return tableview(values) {
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
            placeholder = profilingIndicator
            autoResizeColumn()
        }
    }

    private fun TableView<JitProfilingInfo>.autoResizeColumn() {
        // todo: разобраться с изменением ширины колонок
        items.onChange {
            val scrollbarWidth = this.lookupAll(".scroll-bar")
                    .map { it as ScrollBar }
                    .firstOrNull { bar -> bar.orientation == Orientation.VERTICAL }?.widthProperty()
            var usedWidth: DoubleBinding = columns[1].widthProperty()
                    .add(columns[2].widthProperty()).add(columns[3].widthProperty())
            if (scrollbarWidth != null) usedWidth = usedWidth.add(scrollbarWidth)
            var freeWidth = this.widthProperty().subtract(usedWidth)
            if (scrollbarWidth != null) freeWidth = freeWidth.subtract(SimpleDoubleProperty(3.0))
            columns[0].prefWidthProperty().bind(freeWidth)
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
    logParser.splitLogFileOpen(File(jitLogFile))
    loadClassPathFromLogIfNeeded(logParser)
    logParser.configureDisposableClassLoaderOpen()
    logParser.parseLogFileOpen()
    return logParser.model ?: kotlin.error("Couldn't build Jit data model")
}

private fun loadClassPathFromLogIfNeeded(logParser: HSLPWrapper) {
    // не загружаем классы из лога, если уже указан class path
    if (logParser.config.configuredClassLocations.isNotEmpty()) return
    val maybeClassPath = logParser.splitLog.headerLines
            .filter { it.line.startsWith("java.class.path") }
            .map { it.line.split("=")[1].trim() }
            .map { if (it.isNotEmpty()) StringUtil.textToList(it, ":") else emptyList() }
            .firstOrNull()
    val classPath = HashSet(logParser.config.configuredClassLocations)
    classPath.addAll(maybeClassPath.orEmpty())
    logParser.config.setClassLocations(classPath.toList())
    logParser.config.allClassLocations.addAll(maybeClassPath.orEmpty())
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

data class JitProfilingInfo(val fullMethodName: String,
                            val totalCompilationTime: Long,
                            val decompilationCount: Int,
                            val currentNativeSize: Int,
                            val currentBytecodeSize: Int,
                            val events: List<JITEvent>,
                            val model: JITDataModel) {
    val methodNameProperty = SimpleStringProperty(fullMethodName)
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