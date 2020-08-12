import javafx.scene.control.*
import javafx.scene.layout.*
import tornadofx.*

fun main(args: Array<String>) {
    launch<Profiler>(args)
}

class Profiler : App(PV::class)

class PV: View("Profiler") {
    private lateinit var profilerView: JitProfilingInformationExtractor

    override val root = vbox {
        tabpane {
            val view = JitProfilingInformationNode()
            profilerView = view
            tab("Scan processes") {
                content = ProcessAnalyserView(view).root
            }
            tab("Profiling information") {
                content = view.root
            }
            tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
            hgrow = Priority.ALWAYS
            vgrow = Priority.ALWAYS
        }
    }
}