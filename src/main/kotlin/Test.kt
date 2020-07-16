import org.adoptopenjdk.jitwatch.core.IJITListener
import org.adoptopenjdk.jitwatch.model.JITEvent
import org.adoptopenjdk.jitwatch.parser.hotspot.HotSpotLogParser
import java.io.File

fun main(args: Array<String>) {
    val jitLogFile = args[0]
    val logParser = HSLPWrapper(JitListenerNoOp())
    logParser.reset()
    logParser.configureDisposableClassLoaderOpen()
    logParser.splitLogFileOpen(File(jitLogFile))
    logParser.parseLogFileOpen()
    val model = logParser.model
    val eventListCopy = model.eventListCopy
    for (event in eventListCopy) {
        event.eventType
    }
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