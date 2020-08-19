import javafx.beans.binding.*
import javafx.beans.property.*
import javafx.event.*
import javafx.geometry.*
import javafx.scene.*
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.scene.text.*
import tornadofx.*

class PackagingInformationNode: Parent() {
    private lateinit var rootItem: TreeItem<PackagingTreeObject>
    private lateinit var treePackageTable: TreeTableView<PackagingTreeObject>
    private val itemFactory
            = { n: PackagingTreeObject, list: List<TreeItem<PackagingTreeObject>> ->
        val item = TreeItem(n)
        item.children.addAll(list)
        item
    }

    val root = vbox {
        rootItem = itemFactory(root(), emptyList())
        treePackageTable = treetableview(rootItem) {
            column("Package", PackagingTreeObject::levelPackageNameProperty)
            column("Total compilation time (ms)", PackagingTreeObject::totalCompilationTimeProperty)
            column("Current native size", PackagingTreeObject::currentNativeSizeProperty)
            isShowRoot = false
            autoResizeColumn()
            hgrow = Priority.ALWAYS
            vgrow = Priority.ALWAYS
        }
    }

    fun setJitProfilingInfos(jitProfilingInfos: List<JitProfilingInfo>) {
        val root = root(walkPackageTree(jitProfilingInfos, 0))
        val rootItem = buildItemTree(root)
        treePackageTable.root = rootItem
        treePackageTable.refresh()
    }

    private fun buildItemTree(packagingTreeObject: PackagingTreeObject): TreeItem<PackagingTreeObject> {
        val children = packagingTreeObject.children.map { buildItemTree(it) }.toList()
        return itemFactory(packagingTreeObject, children)
    }

    private fun TreeTableView<PackagingTreeObject>.autoResizeColumn() {
        // todo: разобраться с изменением ширины колонок
        onMouseClicked = EventHandler {
            val scrollbarWidth = this.lookupAll(".scroll-bar")
                    .map { it as ScrollBar }
                    .firstOrNull { bar -> bar.orientation == Orientation.VERTICAL && bar.isVisible }?.widthProperty()
            var usedWidth: DoubleBinding = computeUsedWidth()
            if (scrollbarWidth != null) usedWidth = usedWidth.add(scrollbarWidth)
            var freeWidth = this.widthProperty().subtract(usedWidth)
            if (scrollbarWidth != null) freeWidth = freeWidth.subtract(SimpleDoubleProperty(3.0))
            columns[0].prefWidthProperty().bind(freeWidth)
        }
        for (i in 1 until columns.size) {
            columns[i].prefWidth = Text(columns[i].text).layoutBounds.width + 30.0
            columns[i].isResizable = false
        }
        val usedWidth: DoubleBinding = computeUsedWidth()
        columns[0].prefWidthProperty().bind(this.widthProperty().subtract(usedWidth))
    }

    private fun TreeTableView<PackagingTreeObject>.computeUsedWidth(): DoubleBinding {
        var usedWidth: DoubleBinding = SimpleDoubleProperty(0.0).add(0)
        for (i in 1 until columns.size) {
            usedWidth = usedWidth.add(columns[i].widthProperty())
        }
        return usedWidth
    }
}

abstract class PackagingTreeObject {
    abstract val totalCompilationTime: Long
    abstract val currentNativeSize: Long
    abstract val levelPackageNameProperty: SimpleStringProperty
    abstract val totalCompilationTimeProperty: SimpleLongProperty
    abstract val currentNativeSizeProperty: SimpleLongProperty
    abstract val children: List<PackagingTreeObject>
}

fun walkPackageTree(infos: List<JitProfilingInfo>, level: Int): List<PackagingTreeObject> {
    val packagingTreeObjects = ArrayList<PackagingTreeObject>()
    val packageNameToChildren = HashMap<String, ArrayList<JitProfilingInfo>>()
    val classNameToChildren = HashMap<String, ArrayList<JitProfilingInfo>>()
    for (jitProfilingInfo in infos) {
        val levelName = jitProfilingInfo.packageChain[level]
        if (level + 1 == jitProfilingInfo.packageChain.size) {
            classNameToChildren.getOrPut(levelName) { ArrayList() }.add(jitProfilingInfo)
        } else {
            packageNameToChildren.getOrPut(levelName) { ArrayList() }.add(jitProfilingInfo)
        }
    }
    for ((levelPackage, levelChildren) in packageNameToChildren.entries) {
        val children = walkPackageTree(levelChildren, level + 1)
        packagingTreeObjects.add(Package(levelPackage, children))
    }
    for ((levelPackage, levelChildren) in classNameToChildren.entries) {
        packagingTreeObjects.add(Class(levelPackage, levelChildren))
    }
    return packagingTreeObjects
}

val JitProfilingInfo.packageChain: List<String>
    get() = methodPackageChain(fullMethodName)

private fun methodPackageChain(fullMethodName: String): List<String> {
    val substringBefore = fullMethodName.substringBefore("(")
    val packages = substringBefore.split(".")
    return packages.take(packages.size - 1)
}

private fun root(children: List<PackagingTreeObject> = emptyList()): Package = Package("", children)

class Package(levelPackageName: String, override val children: List<PackagingTreeObject>): PackagingTreeObject() {
    override val levelPackageNameProperty = SimpleStringProperty(levelPackageName)
    override val currentNativeSizeProperty = SimpleLongProperty()
    override val totalCompilationTimeProperty = SimpleLongProperty()
    override val totalCompilationTime: Long
    override val currentNativeSize: Long

    init {
        var time = 0L
        var nativeSize = 0L
        for (child in children) {
            time += child.totalCompilationTime
            nativeSize += child.currentNativeSize
        }
        totalCompilationTime = time
        currentNativeSize = nativeSize
        totalCompilationTimeProperty.set(totalCompilationTime)
        currentNativeSizeProperty.set(currentNativeSize)
    }
}

class Class(className: String, classMethodsInfos: List<JitProfilingInfo>): PackagingTreeObject() {
    override val children = emptyList<PackagingTreeObject>()
    override val levelPackageNameProperty = SimpleStringProperty(className)
    override val currentNativeSizeProperty = SimpleLongProperty()
    override val totalCompilationTimeProperty = SimpleLongProperty()
    override val totalCompilationTime: Long
    override val currentNativeSize: Long

    init {
        var time = 0L
        var nativeSize = 0L
        for (child in classMethodsInfos) {
            time += child.totalCompilationTimeFromTo(Long.MIN_VALUE, Long.MAX_VALUE)
            nativeSize += child.currentNativeSizeAtTimestamp(Long.MAX_VALUE)
        }
        totalCompilationTime = time
        currentNativeSize = nativeSize
        totalCompilationTimeProperty.set(totalCompilationTime)
        currentNativeSizeProperty.set(currentNativeSize)
    }
}