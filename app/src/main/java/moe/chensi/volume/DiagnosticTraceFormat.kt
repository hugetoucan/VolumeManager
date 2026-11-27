package moe.chensi.volume

internal object DiagnosticTraceFormatter {
    fun format(
        timestampMs: Long,
        surface: String,
        implementation: String,
        event: String,
        details: String
    ): String =
        "$timestampMs surface=$surface impl=$implementation event=$event $details".trimEnd()
}

internal data class TraceSnapshot(val revision: Long, val text: String)

internal class BoundedTraceBuffer(
    private val maxEntries: Int = 160,
    private val maxCharacters: Int = 24 * 1024,
    private val maxLineCharacters: Int = 320
) {
    private val entries = ArrayDeque<String>()
    private var characterCount = 0
    private var revision = 0L

    init {
        require(maxEntries > 0)
        require(maxCharacters > 0)
        require(maxLineCharacters > 0)
        require(maxLineCharacters <= maxCharacters)
    }

    @Synchronized
    fun append(line: String) {
        val normalized = line.replace('\r', ' ').replace('\n', ' ').take(maxLineCharacters)
        if (entries.isNotEmpty()) {
            characterCount++
        }
        entries.addLast(normalized)
        characterCount += normalized.length
        revision++

        while (entries.size > maxEntries || characterCount > maxCharacters) {
            val removed = entries.removeFirst()
            characterCount -= removed.length
            if (entries.isNotEmpty()) {
                characterCount--
            }
        }
    }

    @Synchronized
    fun snapshot(): String = entries.joinToString("\n")

    @Synchronized
    fun snapshotWithRevision(): TraceSnapshot =
        TraceSnapshot(revision = revision, text = entries.joinToString("\n"))

    @Synchronized
    fun clear() {
        entries.clear()
        characterCount = 0
        revision++
    }
}
