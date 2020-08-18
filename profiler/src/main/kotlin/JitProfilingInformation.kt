@file:Suppress("DEPRECATED_IDENTITY_EQUALS")

import javafx.beans.property.*
import javafx.collections.*
import javafx.concurrent.Task
import javafx.geometry.*
import javafx.scene.*
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.scene.text.*
import javafx.util.*
import org.adoptopenjdk.jitwatch.chain.*
import org.adoptopenjdk.jitwatch.core.*
import org.adoptopenjdk.jitwatch.core.JITWatchConstants.*
import org.adoptopenjdk.jitwatch.model.*
import org.adoptopenjdk.jitwatch.parser.hotspot.*
import org.adoptopenjdk.jitwatch.util.*
import tornadofx.*
import java.io.*
import java.util.*
import java.util.concurrent.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet

interface JitProfilingInformationExtractor {
    fun setProfilingInfoFrom(startTime: Long, fromTime: Long)
    fun setProfilingInfoTo(startTime: Long, toTime: Long)
    fun clearProfilingInfoInterval()
    fun startProfiling(file: File)
}

class JitProfilingInformationNode : JitProfilingInformationExtractor, Parent() {
    private lateinit var profilingInfo: List<JitProfilingInfo>
    private val selectedProcessInfo = SimpleObjectProperty<File>()
    private val values = FXCollections.observableArrayList<JitProfilingInfo>()
    private val selectedMethod = SimpleObjectProperty<JitProfilingInfo>()
    private val profileCompleted = SimpleObjectProperty<Boolean>(false)
    private lateinit var table: TableView<JitProfilingInfo>
    private lateinit var profilingIndicator: ProgressIndicator
    private val executor = getExecutor()
    private var loader: Task<List<JitProfilingInfo>>? = null
    private val profilingInfoFrom = SimpleObjectProperty<Long>()
    private val profilingInfoTo = SimpleObjectProperty<Long>()
    private lateinit var fromField: TextField
    private lateinit var toField: TextField

    val root = vbox {
        hbox {
            button("Choose file to profile & start profiling") {
                action {
                    val file = chooseFile("Select file", arrayOf())
                    if (file.isNotEmpty()) {
                        startProfiling(file[0])
                    }
                }
            }
            button("Cancel") {
                enableWhen { profileCompleted.isNull }
                action {
                    loader?.cancel()
                }
            }
            profilingIndicator = progressindicator {
                isVisible = false
            }
            textfield {
                promptText = "Enter class name"
                enableWhen { profileCompleted.isEqualTo(true) }
                prefWidth = 400.0
                textProperty().addListener { _, _, new ->
                    val filter = profilingInfo.filter { it.fullMethodName.toLowerCase().startsWith(new, true)
                            || it.fullMethodName.toLowerCase().contains(new, true) }
                    val sortOrder = ArrayList(table.sortOrder)
                    values.setAll(filter)
                    table.sortOrder.setAll(sortOrder)
                }
            }
            addFiltering()
        }
        table = createJitProfilingInfoTable()
    }

    override fun startProfiling(file: File) {
        if (profilingInfoFrom.value != null && profilingInfoTo.value != null && profilingInfoFrom.value > profilingInfoTo.value) {
            warning("Profiling interval is incorrect")
        }
        selectedProcessInfo.set(file)
        values.clear()
        profilingIndicator.isVisible = true
        loadJitProfilingInfo()
        profileCompleted.set(null)
    }

    private fun loadJitProfilingInfo() {
        val listLoader = object : Task<List<JitProfilingInfo>>() {
            init {
                setOnSucceeded {
                    profilingInfo = value
                    values.setAll(value)
                    filterJitProfilingInfo()
                    profilingIndicator.isVisible = false
                    profileCompleted.set(true)
                }
                setOnCancelled {
                    profilingIndicator.isVisible = false
                    profileCompleted.set(false)
                }
            }

            override fun call(): List<JitProfilingInfo> = profile(selectedProcessInfo.get().absolutePath)
        }
        loader = listLoader
        executor.submit(listLoader)
    }

    private fun filterJitProfilingInfo() {
        if (profilingInfoFrom.value != null && profilingInfoTo.value != null && profilingInfoFrom.value > profilingInfoTo.value) {
            warning("Profiling interval is incorrect")
        }
        val from = profilingInfoFrom.value ?: Long.MIN_VALUE
        val to = profilingInfoTo.value ?: Long.MAX_VALUE
        for (profilingInfo in profilingInfo) {
            profilingInfo.totalCompilationTimeProperty.set(profilingInfo.totalCompilationTimeFromTo(from, to))
            profilingInfo.compilationCountProperty.set(profilingInfo.compilationCountFromTo(from, to))
            profilingInfo.decompilationCountProperty.set(profilingInfo.decompilationCountFromTo(from, to))
            profilingInfo.currentNativeSizeProperty.set(profilingInfo.currentNativeSizeAtTimestamp(to))
            profilingInfo.inlineIntoCountProperty.set(profilingInfo.inlineIntoCountFromTo(from, to))
        }
        values.setAll(profilingInfo)
    }

    private fun VBox.createJitProfilingInfoTable(): TableView<JitProfilingInfo> {
        return tableview(values) {
            column("Method name", JitProfilingInfo::methodNameProperty).remainingWidth()
            column("Total compilation time (ms)", JitProfilingInfo::totalCompilationTimeProperty)
                    .prefWidth(Text(columns[1].text).layoutBounds.width + 30.0)
            column("Compilation count", JitProfilingInfo::compilationCountProperty)
                    .prefWidth(Text(columns[2].text).layoutBounds.width + 30.0)
            column("Decompilation count", JitProfilingInfo::decompilationCountProperty)
                    .prefWidth(Text(columns[3].text).layoutBounds.width + 30.0)
            column("Current native size", JitProfilingInfo::currentNativeSizeProperty)
                    .prefWidth(Text(columns[4].text).layoutBounds.width + 30.0)
            column("Inlined into", JitProfilingInfo::inlineIntoCountProperty)
                    .prefWidth(Text(columns[5].text).layoutBounds.width + 30.0)
            bindSelected(selectedMethod)
            setPrefSize(667.0 * 2, 376.0 * 2)
            columnResizePolicy = SmartResize.POLICY
            vgrow = Priority.ALWAYS
            hgrow = Priority.ALWAYS

            rowFactory = createDoubleClickHandlerRowFactory({ TableRow<JitProfilingInfo>() },
                                                            { DetailedMethodInfoView(it, profilingInfoFrom.value,
                                                                    profilingInfoTo.value).openWindow() })
            placeholder = profilingIndicator
        }
    }

    private fun HBox.addFiltering() {
        label {
            text = "Filter info from"
            padding = Insets(5.0, 10.0, 0.0, 10.0)
        }
        fromField = getStampTextField(profilingInfoFrom)
        label {
            text = "to"
            padding = Insets(5.0, 10.0, 0.0, 0.0)
        }
        toField = getStampTextField(profilingInfoTo)
        button("Filter") {
            action {
                filterJitProfilingInfo()
            }
        }
        button("Clear") {
            action {
                clearProfilingInfoInterval()
                filterJitProfilingInfo()
            }
        }
    }

    private fun HBox.getStampTextField(stamp: SimpleObjectProperty<Long>): TextField {
        val textfield = textfield {
            filterInput { it.controlNewText.isDouble() }
            prefWidth = 100.0
            promptText = "123.456"
            textProperty().addListener { _, _, new ->
                val stampOrNull = new.toDoubleOrNull()
                if (stampOrNull == null) {
                    stamp.set(null)
                } else {
                    // перевод в милисекунды
                    val from = stampOrNull * 1000
                    stamp.set(from.toLong())
                }
            }
        }
        label {
            text = "sec"
            padding = Insets(5.0, 10.0, 0.0, 2.5)
        }
        return textfield
    }

    private fun getExecutor() = ThreadPoolExecutor(1, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
            SynchronousQueue<Runnable>(), getThreadFactory())

    private fun getThreadFactory(): (Runnable?) -> Thread = { r: Runnable? ->
        val t = Thread(r)
        t.isDaemon = true
        t
    }

    override fun setProfilingInfoFrom(startTime: Long, fromTime: Long) {
        profilingInfoFrom.set(fromTime - startTime)
        fromField.text = ((fromTime - startTime) / 1000.0).toString()
    }

    override fun setProfilingInfoTo(startTime: Long, toTime: Long) {
        profilingInfoTo.set(toTime - startTime)
        toField.text = ((toTime - startTime)/ 1000.0).toString()
    }

    override fun clearProfilingInfoInterval() {
        profilingInfoFrom.set(null)
        profilingInfoTo.set(null)
        fromField.clear()
        toField.clear()
    }
}

fun <S> createDoubleClickHandlerRowFactory(rowCreator: () -> TableRow<S>, actionOnDoubleClick: (S) -> Unit):
        Callback<TableView<S>, TableRow<S>> = Callback {
    val row: TableRow<S> = rowCreator.invoke()
    row.setOnMouseClicked { event ->
        if (event.clickCount === 2 && !row.isEmpty) {
            val rowData: S = row.item
            actionOnDoubleClick.invoke(rowData)
        }
    }
    row
}

fun getSignature(member: IMetaMember) = member.fullyQualifiedMemberName + "(" + toString(member.paramTypeNames) + ")"
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
    val infos = ArrayList<JitProfilingInfo>()
    val nameToInfo = HashMap<String, JitProfilingInfo>()

    for (name in map.keys) {
        val events = map[name]!!
        val jitProfilingInfo = if (events.isEmpty()) {
            JitProfilingInfo(name, 0, 0, LongArray(0), LongArray(0),  IntArray(0),
                    0, LongArray(0), events, model)
        } else {
            createJitProfilingInfo(name, events[0].eventMember, events, model)
        }
        infos += jitProfilingInfo
        nameToInfo[name] = jitProfilingInfo
    }
    fillInlinedIntoInfo(map, nameToInfo)
    return infos
}

private fun createJitProfilingInfo(name: String, eventMember: IMetaMember, events: MutableList<JITEvent>, model: JITDataModel): JitProfilingInfo {
    val compilationCount = eventMember.compilations.size
    val compilationToTimestamp = LongArray(compilationCount)
    val compilationToCompilationTimeMs = LongArray(compilationCount)
    val compilationToBytecodeSize = IntArray(compilationCount)
    val compilationToNativeSize = IntArray(compilationCount)
    val decompilationCount = eventMember.compilations.mapNotNull { it.compiledAttributes["decompiles"] }.map { it.toInt() }.max() ?: 0
    val decompilationToTimestamp = LongArray(decompilationCount)
    var currentDecompilation = 0
    for ((index, compilation) in eventMember.compilations.withIndex()) {
        compilationToTimestamp[index] = compilation.stampNMethodEmitted
        compilationToCompilationTimeMs[index] = compilation.compilationDuration
        compilationToBytecodeSize[index] = compilation.bytecodeSize
        compilationToNativeSize[index] = compilation.nativeSize
        val decompiles = compilation.compiledAttributes["decompiles"] ?: continue
        val decompilesBeforeTaskQueued = decompiles.toInt()
        // если изменилось количество декомпиляций с прошлой компиляции
        if (decompilesBeforeTaskQueued > currentDecompilation) {
            // считаем временем декомпиляции – момент, когда задачу на компиляцию добавили в очередь, так как в jit логе
            // нет события "декомпиляция"
            decompilationToTimestamp[decompilesBeforeTaskQueued - 1] = compilation.stampTaskQueued
            currentDecompilation = decompilesBeforeTaskQueued - 1
        }
    }

    return JitProfilingInfo(name, eventMember.lastCompilation?.bytecodeSize ?: 0, compilationCount,
            compilationToTimestamp, compilationToCompilationTimeMs, compilationToNativeSize, decompilationCount,
            decompilationToTimestamp, events, model)
}

private fun fillInlinedIntoInfo(map: HashMap<String, MutableList<JITEvent>>, nameToInfo: HashMap<String, JitProfilingInfo>) {
    val inlinedIntoForMethod = HashMap<String, TreeSet<InlineIntoInfo>>()
    for (name in map.keys) {
        val jitProfilingInfo = nameToInfo[name]!!
        val compileTree = getCompileTrees(jitProfilingInfo)
        for (tree in compileTree) {
            val compilation = tree.compilation.signature
            for (child in tree.children) {
                if (child == null || child.member == null || !child.isInlined) continue
                var sortInlineInto = compareBy<InlineIntoInfo> { it.caller.fullMethodName }
                sortInlineInto = sortInlineInto.thenBy { it.compilation }
                val orElse = inlinedIntoForMethod.getOrPut(getSignature(child.member)) { TreeSet(sortInlineInto) }
                val reason = child.tooltipText?.split(S_NEWLINE)?.filter { it.startsWith("Inlined") }
                        ?.map { it.split(", ")[1] }?.first() ?: "Unknown"
                orElse += InlineIntoInfo(jitProfilingInfo, tree.compilation.stampNMethodEmitted, compilation, reason)
            }
        }
        jitProfilingInfo.compileTrees = compileTree
    }
    for (name in map.keys) {
        val jitProfilingInfo = nameToInfo[name]!!
        val orEmpty = inlinedIntoForMethod[name].orEmpty()
        jitProfilingInfo.inlinedInto = orEmpty.toList()
        jitProfilingInfo.inlineIntoCountProperty.set(orEmpty.size)
    }
}

data class InlineIntoInfo(val caller: JitProfilingInfo, val inlinedIntoCompilationTimestamp: Long, val compilation: String,
                          val reason: String) {
    val methodNameProperty = SimpleStringProperty(caller.fullMethodName)
    val compilationProperty = SimpleStringProperty(compilation)
    val reasonProperty = SimpleStringProperty(reason)
}

data class JitProfilingInfo(val fullMethodName: String,
                            val bytecodeSize: Int,
                            val compilationsCount: Int,
                            val compilationToTimestamp: LongArray,
                            val compilationToCompilationTimeMs: LongArray,
                            val compilationToNativeSize: IntArray,
                            val decompilationCount: Int,
                            val decompilationToTimestamp: LongArray,
                            val events: List<JITEvent>,
                            val model: JITDataModel) {
    val methodNameProperty = SimpleStringProperty(fullMethodName)
    val totalCompilationTimeProperty = SimpleLongProperty(compilationToCompilationTimeMs.sum())
    val compilationCountProperty = SimpleIntegerProperty(compilationsCount)
    val decompilationCountProperty = SimpleIntegerProperty(decompilationCount)
    val currentNativeSizeProperty = SimpleIntegerProperty(compilationToNativeSize.last())
    val inlineIntoCountProperty = SimpleIntegerProperty(0)
    lateinit var compileTrees: List<CompileNode>
    lateinit var inlinedInto: List<InlineIntoInfo>

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as JitProfilingInfo
        if (fullMethodName != other.fullMethodName) return false
        return true
    }

    override fun hashCode(): Int {
        return fullMethodName.hashCode()
    }
}

fun JitProfilingInfo.totalCompilationTimeFromTo(from: Long, to: Long): Long {
    var totalTime = 0L
    for (i in 0 until this.compilationsCount) {
        if (this.compilationToTimestamp[i] in from..to) {
            totalTime += this.compilationToCompilationTimeMs[i]
        }
    }
    return totalTime
}

fun JitProfilingInfo.compilationCountFromTo(from: Long, to: Long): Int {
    return elementsCountFromTo(this.compilationToTimestamp, from, to)
}

fun JitProfilingInfo.decompilationCountFromTo(from: Long, to: Long): Int {
    return elementsCountFromTo(this.decompilationToTimestamp, from, to)
}

private fun elementsCountFromTo(timestamps: LongArray, from: Long, to: Long): Int {
    var count = 0
    for (i in timestamps.indices) {
        if (timestamps[i] in from..to) {
            count += 1
        }
    }
    return count
}

fun JitProfilingInfo.currentNativeSizeAtTimestamp(to: Long): Int {
    var toIndex = this.compilationToTimestamp.binarySearch(to)
    // если указывает на -1, то это значит, что меньше элемента stampArray[0]
    if (toIndex == -1) return 0
    if (toIndex < 0) {
        // переводим в указание на позицию, куда вставить элемент, и вычитаем единицу, чтобы указывать на тот элемент,
        // который меньше
        toIndex = -(toIndex + 1) - 1
    }
    return this.compilationToNativeSize[toIndex]
}

fun JitProfilingInfo.inlineIntoCountFromTo(from: Long, to: Long): Int {
    return this.inlinedInto
            .filter { it.inlinedIntoCompilationTimestamp in from..to }
            .count()
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