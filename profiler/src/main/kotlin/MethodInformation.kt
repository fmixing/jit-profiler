import javafx.beans.property.*
import javafx.collections.*
import javafx.geometry.*
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.scene.paint.*
import javafx.scene.text.*
import javafx.util.*
import org.adoptopenjdk.jitwatch.chain.*
import org.adoptopenjdk.jitwatch.model.*
import tornadofx.*
import kotlin.math.*


class DetailedMethodInfoView(private val jitProfilingInfo: JitProfilingInfo,
                             private val fromFilter: Long?,
                             private val toFilter: Long?): View("Detailed info${filterInfo(fromFilter, toFilter)}") {
    private val from = fromFilter ?: Long.MIN_VALUE
    private val to = toFilter ?: Long.MAX_VALUE
    private val deoptimizationInfoValues = FXCollections.observableArrayList(getDeoptimizationInfo(jitProfilingInfo)
            .filter { it.deoptimizationTimestamp in from..to })
    private val compileTrees = jitProfilingInfo.compileTrees
    private val maxLabelWidth = 667.0
    private val inlineIntoValues = FXCollections.observableArrayList(jitProfilingInfo.inlinedInto
            .filter { it.inlinedIntoCompilationTimestamp in from..to })
    private lateinit var inlinedInfoTabPane: TabPane

    override val root = vbox {
        hbox {
            vbox {
                if (fromFilter != null && toFilter != null) {
                    button("Show full info") {
                        action {
                            DetailedMethodInfoView(jitProfilingInfo, null, null).openWindow()
                        }
                    }
                }
                label {
                    val from = fromFilter ?: Long.MIN_VALUE
                    val to = toFilter ?: Long.MAX_VALUE
                    text = "Method name: ${jitProfilingInfo.fullMethodName}\n" +
                            "Total compilation time (ms): ${jitProfilingInfo.totalCompilationTimeFromTo(from, to)}\n" +
                            "Compilations: ${jitProfilingInfo.compilationCountFromTo(from, to)}\n" +
                            "Decompilations: ${jitProfilingInfo.decompilationCountFromTo(from, to)}\n" +
                            "Current native size: ${jitProfilingInfo.currentNativeSizeAtTimestamp(to)}\n" +
                            "Bytecode size: ${jitProfilingInfo.bytecodeSize}"
                    padding = Insets(10.0, 10.0, 10.0, 10.0)
                    val textWidth = Text(text).layoutBounds.width + 20.0
                    prefWidth = min(maxLabelWidth, textWidth)
                    if (textWidth > prefWidth) {
                        tooltip = Tooltip(jitProfilingInfo.fullMethodName)
                    }
                }
            }
            vbox {
                label {
                    text = "Deoptimizations (uncommon traps)"
                }
                tableview(SimpleListProperty(deoptimizationInfoValues)) {
                    column("# of compilation", DeoptimizationInfo::compilationIndexProperty)
                    column("Count", DeoptimizationInfo::countProperty)
                    column("Compiler", DeoptimizationInfo::compilerProperty)
                    column("Reason", DeoptimizationInfo::reasonProperty)
                    column("Action", DeoptimizationInfo::actionProperty)
                    setPrefSize(667.0, 376.0 / 2)
                    columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY
                    hgrow = Priority.ALWAYS
                    vgrow = Priority.ALWAYS
                    rowFactory = createDoubleClickHandlerRowFactory({ TableRow<DeoptimizationInfo>() },
                            { showPathToDeoptimizationReason(it) })
                    tooltip = Tooltip("Tap twice to see deoptimization chain")
                }
                alignment = Pos.CENTER
                hgrow = Priority.ALWAYS
                vgrow = Priority.ALWAYS
            }
        }
        vbox {
            createCompilationsSection()
            hgrow = Priority.ALWAYS
            vgrow = Priority.ALWAYS
        }
    }

    private fun VBox.createCompilationsSection() {
        separator {}
        label {
            text = "Inlining information"
            padding = Insets(2.0, 2.0, 2.0, 10.0)
            val oldFont = font
            font = Font(oldFont.size + 2)
        }
        inlinedInfoTabPane = tabpane {
            for (node in compileTrees) {
                val compilation = node.compilation
                if (node.compilation.stampNMethodEmitted in from..to) {
                    tab(compilation.signature + ", " + compilation.compilationDuration + "ms") {
                        hbox {
                            add(buildInlineTree(node))
                            label(graphic = getColorKeys()) {
                                padding = Insets(10.0, 10.0, 10.0, 10.0)
                                maxWidth = Text(InlinedInfoKey.values().joinToString("\n") { it.keyName() })
                                        .layoutBounds.width + 20.0
                            }
                        }
                    }
                }
            }
            tab("Inlined into (" + inlineIntoValues.size + ")") {
                add(createInlinedIntoTabInfo())
            }
            tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
            prefHeight = 376.0
            hgrow = Priority.ALWAYS
            vgrow = Priority.ALWAYS
        }
    }

    private fun buildInlineTree(node: CompileNode) : TreeView<CompileNode> {
        val itemFactory = { n: CompileNode ->
            val item = TreeItem(n)
            item.isExpanded = true
            item
        }
        return treeview(itemFactory(node)) {
            populate(itemFactory = itemFactory) {
                if (it.value.children.isNotEmpty())
                    it.value.children
                else
                    null
            }
            cellFactory = factory(node)
            hgrow = Priority.ALWAYS
            vgrow = Priority.ALWAYS
        }
    }

    private fun showPathToDeoptimizationReason(deoptimizationInfo: DeoptimizationInfo) {
        inlinedInfoTabPane.selectionModel.select(deoptimizationInfo.compilationIndex)
        if (deoptimizationInfo.deoptimizationChain.isEmpty()) return
        @Suppress("UNCHECKED_CAST")
        val treeView = inlinedInfoTabPane.selectionModel.selectedItem.content.getChildList()!![0] as TreeView<CompileNode>
        val root = treeView.root
        expandTreeView(root, false)
        dfs(0, deoptimizationInfo.deoptimizationChain, root)
    }

    private fun expandTreeView(item: TreeItem<CompileNode>?, expand: Boolean) {
        if (item != null && !item.isLeaf) {
            item.isExpanded = expand
            for (child in item.children) {
                expandTreeView(child, expand)
            }
        }
    }

    private fun dfs(pathId: Int, path: List<IMetaMember>, treeItem: TreeItem<CompileNode>): Boolean {
        check(path.size > pathId)
        var found = false
        for (tree in treeItem.children) {
            if (tree.value.member != path[pathId]) {
                continue
            }
            if (pathId + 1 == path.size) {
                found = true
                tree.isExpanded = true
                continue
            }
            found = found || dfs(pathId + 1, path, tree)
        }
        if (found) {
            treeItem.isExpanded = true
        }
        return found
    }

    private fun factory(root: CompileNode): Callback<TreeView<CompileNode>, TreeCell<CompileNode>> = Callback {
        object : TreeCell<CompileNode>() {
            override fun updateItem(item: CompileNode?, empty: Boolean) {
                super.updateItem(item, empty)
                if (!empty) {
                    textFill = InlinedInfoKey.values().first { it.check(treeItem.value) }.color()
                }
                when {
                    empty -> {
                        text = null
                        tooltip = null
                    }
                    treeItem.value == root -> {
                        text = if (item!!.member == null) getInfoWhenMemberAbsent(item) else getSignature(item.member)
                        tooltip = Tooltip("Root node\n${getNativeSizeInfo(root)}")
                    }
                    else -> {
                        text = if (item!!.member == null) getInfoWhenMemberAbsent(item) else getSignature(item.member)
                        tooltip = Tooltip(item.tooltipText)
                    }
                }
            }

            private fun getNativeSizeInfo(node: CompileNode) = "Final native size: " + (node.compilation?.nativeSize ?: "unknown")
        }
    }

    private fun getInfoWhenMemberAbsent(node: CompileNode): String {
        val prefix = if (node.isVirtualCall) "Virtual call for " else ""
        val suffix = if (node.holder != null && node.methodName != null) "${node.holder}#${node.methodName}" else "Unknown"
        return "$prefix$suffix"
    }

    private fun getColorKeys(): TextFlow = textflow {
        InlinedInfoKey.values().forEach {
            val text = Text(it.keyName() + "\n")
            text.fill = it.color()
            add(text)
        }
    }

    private fun createInlinedIntoTabInfo(): TableView<InlineIntoInfo> {
        return tableview(SimpleListProperty(inlineIntoValues)) {
            column("Name", InlineIntoInfo::methodNameProperty).remainingWidth()
            column("Compiler", InlineIntoInfo::compilationProperty)
            column("Reason", InlineIntoInfo::reasonProperty)
            prefHeight = 376.0 / 2
            hgrow = Priority.ALWAYS
            vgrow = Priority.ALWAYS
            rowFactory = createDoubleClickHandlerRowFactory({ TableRow<InlineIntoInfo>() },
                                                            { DetailedMethodInfoView(it.caller, fromFilter, toFilter)
                                                                    .openWindow() })
            columnResizePolicy = SmartResize.POLICY
        }
    }

    private enum class InlinedInfoKey {
        IsInlined {
            override fun color(): Color = Color.GREEN
            override fun check(node: CompileNode) = node.isInlined
            override fun keyName() = "Inlined"
        },
        IsCompiled {
            override fun color(): Color = Color.RED
            override fun check(node: CompileNode) = node.isCompiled
            override fun keyName() = "Compiled"
        },
        IsVirtual {
            override fun color(): Color = Color.PURPLE
            override fun check(node: CompileNode) = node.isVirtualCall
            override fun keyName() = "Virtual call"
        },
        IsNotCompiled {
            override fun color(): Color = Color.BLACK
            override fun check(node: CompileNode) = !node.isCompiled
            override fun keyName() = "Not compiled"
        };

        abstract fun color(): Color
        abstract fun check(node: CompileNode): Boolean
        abstract fun keyName(): String
    }
}

fun filterInfo(fromFilter: Long?, toFilter: Long?): String {
    if (fromFilter == null && toFilter == null) return ""
    val from = if (fromFilter != null) " from ${fromFilter / 1000.0}" else ""
    val to = if (toFilter != null) " to ${toFilter / 1000.0}" else ""
    return ", filtered$from$to"
}

private fun getDeoptimizationInfo(jitProfilingInfo: JitProfilingInfo): List<DeoptimizationInfo> {
    if (jitProfilingInfo.events.isEmpty()) return emptyList()

    val deoptimizationInfos = HashSet<DeoptimizationInfo>()
    val compilations = jitProfilingInfo.events[0].eventMember.compilations

    val deoptimizationEvents = jitProfilingInfo.model.deoptimizationEvents
    for (compilation in compilations) {
        val compileID = compilation.compileID
        val compilationDeoptimizations = deoptimizationEvents.filter { it.compileID == compileID }.toList()
        if (compilationDeoptimizations.isEmpty()) continue
        val compilerString = compilation.signature
        val index = compilation.index
        deoptimizationInfos.addAll(compilationDeoptimizations.map { DeoptimizationInfo(it.stamp, index, it.count ?: "0",
                compilerString, it.reason, it.action, it.deoptimizationChain) })
    }
    return deoptimizationInfos.toList().sortedWith(compareBy { it.compilationIndex } )
}

data class DeoptimizationInfo(val deoptimizationTimestamp: Long, val compilationIndex: Int, val count: String,
                              val compiler: String, val reason: String, val action: String,
                              val deoptimizationChain: List<IMetaMember>) {
    val compilationIndexProperty = SimpleIntegerProperty(compilationIndex + 1)
    val countProperty = SimpleStringProperty(count)
    val compilerProperty = SimpleStringProperty(compiler)
    val reasonProperty = SimpleStringProperty(reason)
    val actionProperty = SimpleStringProperty(action)
}