import javafx.beans.property.*
import javafx.collections.*
import javafx.geometry.*
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.scene.text.*
import org.adoptopenjdk.jitwatch.chain.*
import org.adoptopenjdk.jitwatch.model.*
import tornadofx.*

class DetailedMethodInfoView(private val jitProfilingInfo: JitProfilingInfo): View("Detailed info") {
    private val deoptimizationInfoValues = FXCollections.observableArrayList(getDeoptimizationInfo(jitProfilingInfo))
    private val compileTrees = getCompileTree(jitProfilingInfo)

    override val root = vbox {
        hbox {
            label {
                text = "Method name: ${jitProfilingInfo.methodName}\n" +
                        "Total compilation time: ${jitProfilingInfo.totalCompilationTime}\n" +
                        "Decompilations count: ${jitProfilingInfo.decompilationCount}\n" +
                        "Current native size: ${jitProfilingInfo.currentNativeSize}\n" +
                        "Current bytecode size: ${jitProfilingInfo.currentBytecodeSize}"
                padding = Insets(10.0, 10.0, 10.0, 10.0)
                minWidth = Text(text).layoutBounds.width + 20.0
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
        }
        vbox {
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
                    tab("#${compilation.index} ${getCompileInfo(compilation)}") {
                        addChildIfPossible(buildInlineTree(node))
                    }
                }
                tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
                prefHeight = 376.0
            }
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
            cellFormat {
                text = if (it.member == null) "Unknown" else it.member.fullyQualifiedMemberName
            }
            contextMenu = ContextMenu().apply {
                item("Show detailed info").action {
                    selectionModel.selectedItem?.let {
                        information(if (it.value == node) "Root node" else it.value.tooltipText)
                    }
                }
            }
        }
    }
}

private fun getCompileTree(jitProfilingInfo: JitProfilingInfo): List<CompileNode> {
    if (jitProfilingInfo.events.isEmpty()) return emptyList()

    val compileChainWalker = CompileChainWalker(jitProfilingInfo.model)
    val compilations = jitProfilingInfo.events[0].eventMember.compilations
    val compileTrees = ArrayList<CompileNode>()
    for (compilation in compilations) {
        val callTree = compileChainWalker.buildCallTree(compilation)
        compileTrees += callTree
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
        val compilerString = getCompileInfo(compilation)
        val index = compilation.index
        deoptimizationInfos.addAll(compilationDeoptimizations.map { DeoptimizationInfo(index, compilerString, it.reason, it.action) })
    }
    return deoptimizationInfos.toList().sortedWith(compareBy { it.compilationIndex } )
}

private fun getCompileInfo(compilation: Compilation): String {
    return if (compilation.level != -1) return "${compilation.compiler} (Level ${compilation.level})" else compilation.compiler
}

data class DeoptimizationInfo(val compilationIndex: Int, val compiler: String, val reason: String, val action: String) {
    val compilationIndexProperty = SimpleIntegerProperty(compilationIndex)
    val compilerProperty = SimpleStringProperty(compiler)
    val reasonProperty = SimpleStringProperty(reason)
    val actionProperty = SimpleStringProperty(action)
}