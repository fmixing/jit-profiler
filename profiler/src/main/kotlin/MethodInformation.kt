import javafx.beans.property.*
import javafx.collections.*
import javafx.geometry.*
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.scene.paint.*
import javafx.scene.text.*
import javafx.util.*
import org.adoptopenjdk.jitwatch.chain.*
import tornadofx.*
import kotlin.math.*


class DetailedMethodInfoView(private val jitProfilingInfo: JitProfilingInfo): View("Detailed info") {
    private val deoptimizationInfoValues = FXCollections.observableArrayList(getDeoptimizationInfo(jitProfilingInfo))
    private val compileTrees = jitProfilingInfo.compileTrees
    private val inlineIntoValues = FXCollections.observableArrayList(jitProfilingInfo.inlinedInto)
    private val maxLabelWidth = 667.0

    override val root = vbox {
        hbox {
            label {
                text = "Method name: ${jitProfilingInfo.fullMethodName}\n" +
                        "Total compilation time (ms): ${jitProfilingInfo.totalCompilationTime}\n" +
                        "Decompilations: ${jitProfilingInfo.decompilationCount}\n" +
                        "Current native size: ${jitProfilingInfo.currentNativeSize}\n" +
                        "Bytecode size: ${jitProfilingInfo.currentBytecodeSize}"
                padding = Insets(10.0, 10.0, 10.0, 10.0)
                val textWidth = Text(text).layoutBounds.width + 20.0
                prefWidth = min(maxLabelWidth, textWidth)
                if (textWidth > prefWidth) {
                    tooltip = Tooltip(jitProfilingInfo.fullMethodName)
                }
            }
            vbox {
                label {
                    text = "Deoptimizations (uncommon traps)"
                }
                tableview(SimpleListProperty(deoptimizationInfoValues)) {
                    column("# of compilation", DeoptimizationInfo::compilationIndexProperty)
                    column("Compiler", DeoptimizationInfo::compilerProperty)
                    column("Reason", DeoptimizationInfo::reasonProperty)
                    column("Action", DeoptimizationInfo::actionProperty)
                    setPrefSize(667.0, 376.0 / 2)
                    columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY
                    hgrow = Priority.ALWAYS
                    vgrow = Priority.ALWAYS
                }
                alignment = Pos.CENTER
                hgrow = Priority.ALWAYS
                vgrow = Priority.ALWAYS
            }
        }
        vbox {
            createCompilationsSection()
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
        tabpane {
            for (node in compileTrees) {
                val compilation = node.compilation
                tab(compilation.signature + ", " + compilation.compilationDuration + "ms") {
                    add(buildInlineTree(node))
                }
            }
            tab("Inlined into (" + inlineIntoValues.size + ")") {
                add(createInlinedIntoTabInfo())
            }
            tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
            prefHeight = 376.0
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
            contextMenu = ContextMenu().apply {
                item("Show detailed info").action {
                    selectedItem?.let {
                        DetailedMethodInfoView(it.caller).openWindow()
                    }
                }
            }
            columnResizePolicy = SmartResize.POLICY
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
                    it.value.children.filter { child -> child.isInlined }
                else
                    null
            }
            cellFactory = Factory(node)
        }
    }

    class Factory(val root: CompileNode): Callback<TreeView<CompileNode>, TreeCell<CompileNode>> {
        override fun call(param: TreeView<CompileNode>?): TreeCell<CompileNode> {
            return object : TreeCell<CompileNode>() {
                override fun updateItem(item: CompileNode?, empty: Boolean) {
                    super.updateItem(item, empty)
                    when {
                        empty -> {
                            text = null
                            tooltip = null
                        }
                        treeItem.value == root -> {
                            text = if (item!!.member == null) "Unknown" else getSignature(item.member)
                            tooltip = Tooltip("Root node")
                        }
                        else -> {
                            text = if (item!!.member == null) "Unknown" else getSignature(item.member)
                            tooltip = Tooltip(item.tooltipText)
                        }
                    }
                }
            }
        }
    }
}

fun getCompileTrees(jitProfilingInfo: JitProfilingInfo): List<CompileNode> {
    if (jitProfilingInfo.events.isEmpty()) return emptyList()

    val compileChainWalker = CompileChainWalker(jitProfilingInfo.model)
    val compilations = jitProfilingInfo.events[0].eventMember.compilations
    val compileTrees = ArrayList<CompileNode>()
    for (compilation in compilations) {
        val callTree = compileChainWalker.buildCallTree(compilation)
        if (callTree != null) compileTrees += callTree
    }
    return compileTrees
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
        deoptimizationInfos.addAll(compilationDeoptimizations.map { DeoptimizationInfo(index, compilerString, it.reason, it.action) })
    }
    return deoptimizationInfos.toList().sortedWith(compareBy { it.compilationIndex } )
}

data class DeoptimizationInfo(val compilationIndex: Int, val compiler: String, val reason: String, val action: String) {
    val compilationIndexProperty = SimpleIntegerProperty(compilationIndex)
    val compilerProperty = SimpleStringProperty(compiler)
    val reasonProperty = SimpleStringProperty(reason)
    val actionProperty = SimpleStringProperty(action)
}