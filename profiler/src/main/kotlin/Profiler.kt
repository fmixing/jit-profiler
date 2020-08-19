@file:JvmName("JitLogProfiler")

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
            val packagingView = PackagingInformationNode()
            val jitProfilingView = JitProfilingInformationNode(packagingView)
            profilerView = jitProfilingView
            tab("Scan processes") {
                content = ProcessAnalyserView(jitProfilingView).root
            }
            tab("Profiling information") {
                content = jitProfilingView.root
            }
            tab("Packaging information") {
                content = packagingView.root
            }
            tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
            hgrow = Priority.ALWAYS
            vgrow = Priority.ALWAYS
        }
    }
}