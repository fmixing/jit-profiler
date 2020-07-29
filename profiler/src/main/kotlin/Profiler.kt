import javafx.beans.property.*
import javafx.collections.*
import javafx.concurrent.Task
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.scene.text.*
import org.adoptopenjdk.jitwatch.chain.*
import org.adoptopenjdk.jitwatch.core.*
import org.adoptopenjdk.jitwatch.core.JITWatchConstants.*
import org.adoptopenjdk.jitwatch.model.*
import org.adoptopenjdk.jitwatch.parser.hotspot.*
import org.adoptopenjdk.jitwatch.util.*
import tornadofx.*
import java.io.*
import java.util.*
import java.util.concurrent.Executors.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet


fun main(args: Array<String>) {
    launch<Profiler>(args)
}

class Profiler : App(ProfilerView::class)

class ProfilerView : View("Profiler") {
    private lateinit var profilingInfo: List<JitProfilingInfo>
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
                textProperty().addListener { _, _, new ->
                    val filter = profilingInfo.filter { it.fullMethodName.startsWith(new) || it.fullMethodName.contains(new) }
                    values.setAll(filter)
                }
            }
        }
        table = createJitProfilingInfoTable()
    }

    private fun loadJitProfilingInfo() {
        val listLoader = object : Task<List<JitProfilingInfo>>() {
            init {
                setOnSucceeded {
                    profilingInfo = value
                    values.setAll(value)
                    profilingIndicator.isVisible = false
                    profileCompleted.set(true)
                }
            }

            override fun call(): List<JitProfilingInfo> = profile(selectedProcessInfo.get().absolutePath)
        }
        executor.submit(listLoader)
    }

    private fun VBox.createJitProfilingInfoTable(): TableView<JitProfilingInfo> {
        return tableview(values) {
            column("Method name", JitProfilingInfo::methodNameProperty).remainingWidth()
            column("Total compilation time (ms)", JitProfilingInfo::totalCompilationTimeProperty)
                    .prefWidth(Text(columns[1].text).layoutBounds.width + 30.0)
            column("Decompilation count", JitProfilingInfo::decompilationCountProperty)
                    .prefWidth(Text(columns[2].text).layoutBounds.width + 30.0)
            column("Current native size", JitProfilingInfo::currentNativeSizeProperty)
                    .prefWidth(Text(columns[3].text).layoutBounds.width + 30.0)
            column("Inlined into count", JitProfilingInfo::inlineIntoCountProperty)
                    .prefWidth(Text(columns[4].text).layoutBounds.width + 30.0)
            bindSelected(selectedMethod)
            setPrefSize(667.0 * 2, 376.0 * 2)
            columnResizePolicy = SmartResize.POLICY
            vgrow = Priority.ALWAYS
            hgrow = Priority.ALWAYS

            contextMenu = ContextMenu().apply {
                item("Show detailed info").action {
                    selectedItem?.let {
                        DetailedMethodInfoView(it).openWindow()
                    }
                }
            }
            placeholder = profilingIndicator
        }
    }
}

fun getSignature(member: IMetaMember) = member.fullyQualifiedMemberName + "(" + toString(member.paramTypeNames) + ") " + member.returnTypeName
fun toString(signature: Array<String>): String = if (signature.isEmpty()) "" else signature.joinToString(separator = ", ")

private fun profile(jitLogFile: String): List<JitProfilingInfo> {
    val model = parseLogFile(jitLogFile)
    val eventListCopy = model.eventListCopy
    val map = HashMap<String, MutableList<JITEvent>>()
    for (event in eventListCopy) {
        map.getOrPut(getSignature(event.eventMember)) { ArrayList() }.add(event)
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
    val nameToInfo = HashMap<String, JitProfilingInfo>()
    for (name in map.keys) {
        val events = map[name]!!
        val jitProfilingInfo = if (events.isEmpty()) {
            JitProfilingInfo(name, -1, -1, -1, -1, false, events, model)
        } else {
            val time = events[0].eventMember.compilations.map { it.compilationDuration }.sum()
            val nativeSize = events[0].eventMember.lastCompilation?.nativeSize ?: -1
            val bytecodeSize = events[0].eventMember.lastCompilation?.bytecodeSize ?: -1
            val decompilationCount = if (events[0].eventMember.lastCompilation != null) {
                events[0].eventMember.lastCompilation.compiledAttributes["decompiles"]?.toInt() ?: 0
            } else 0
            JitProfilingInfo(name, time, decompilationCount, nativeSize, bytecodeSize, events[0].eventMember.isCompiled, events, model)
        }
        if (nameToInfo.containsKey(name)) kotlin.error("Different jitProfilingInfos for the same method name")
        list += jitProfilingInfo
        nameToInfo[name] = jitProfilingInfo
    }
    fillInlinedIntoInfo(map, nameToInfo)
    return list
}

private fun fillInlinedIntoInfo(map: HashMap<String, MutableList<JITEvent>>, nameToInfo: HashMap<String, JitProfilingInfo>) {
    val inlinedIntoForMethod = HashMap<String, TreeSet<InlineIntoInfo>>()
    for (name in map.keys) {
        val jitProfilingInfo = nameToInfo[name]!!
        val compileTree = getCompileTrees(jitProfilingInfo)
        for (tree in compileTree) {
            val compilation = tree.compilation.signature
            for (child in tree.children) {
                if (child == null || child.member == null) continue
                var sortInlineInto = compareBy<InlineIntoInfo> { it.caller.fullMethodName }
                sortInlineInto = sortInlineInto.thenBy { it.compilation }
                val orElse = inlinedIntoForMethod.getOrPut(getSignature(child.member)) { TreeSet(sortInlineInto) }
                val reason = child.tooltipText?.split(S_NEWLINE)?.filter { it.startsWith("Inlined") }?.map { it.split(", ")[1] }?.first()
                        ?: "Unknown"
                orElse += InlineIntoInfo(jitProfilingInfo, compilation, if (child.isInlined) "Yes" else "No", reason)
            }
        }
        jitProfilingInfo.compileTrees = compileTree
    }
    for (name in map.keys) {
        val jitProfilingInfo = nameToInfo[name]!!
        val orEmpty = inlinedIntoForMethod[name].orEmpty()
        jitProfilingInfo.inlinedInto = orEmpty.toList()
        jitProfilingInfo.inlineIntoCountProperty.set(orEmpty.map { if (it.inlined == "Yes") 1 else 0 }.sum())
    }
}

data class InlineIntoInfo(val caller: JitProfilingInfo, val compilation: String, val inlined: String, val reason: String) {
    val methodNameProperty = SimpleStringProperty(caller.fullMethodName)
    val compilationProperty = SimpleStringProperty(compilation)
    val inlinedProperty = SimpleStringProperty(inlined)
    val reasonProperty = SimpleStringProperty(reason)
}

data class JitProfilingInfo(val fullMethodName: String,
                            val totalCompilationTime: Long,
                            val decompilationCount: Int,
                            val currentNativeSize: Int,
                            val currentBytecodeSize: Int,
                            val compiled: Boolean,
                            val events: List<JITEvent>,
                            val model: JITDataModel) {
    val methodNameProperty = SimpleStringProperty(fullMethodName)
    val totalCompilationTimeProperty = SimpleLongProperty(totalCompilationTime)
    val decompilationCountProperty = SimpleIntegerProperty(decompilationCount)
    val currentNativeSizeProperty = SimpleIntegerProperty(currentNativeSize)
    val inlineIntoCountProperty = SimpleIntegerProperty(0)
    lateinit var compileTrees: List<CompileNode>
    lateinit var inlinedInto: List<InlineIntoInfo>
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