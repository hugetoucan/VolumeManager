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

internal class BoundedTraceBuffer(
    private val maxEntries: Int = 160,
    private val maxCharacters: Int = 24 * 1024,
    private val maxLineCharacters: Int = 320
) {
    private val entries = ArrayDeque<String>()
    private var characterCount = 0

    init {
        require(maxEntries > 0)
        require(maxCharacters > 0)
        require(maxLineCharacters > 0)
        require(maxLineCharacters <= maxCharacters)
    }

    @Synchronized
    fun append(line: String): String {
        val normalized = line.replace('\r', ' ').replace('\n', ' ').take(maxLineCharacters)
        if (entries.isNotEmpty()) {
            characterCount++
        }
        entries.addLast(normalized)
        characterCount += normalized.length

        while (entries.size > maxEntries || characterCount > maxCharacters) {
            val removed = entries.removeFirst()
            characterCount -= removed.length
            if (entries.isNotEmpty()) {
                characterCount--
            }
        }

        return entries.joinToString("\n")
    }

    @Synchronized
    fun snapshot(): String = entries.joinToString("\n")

    @Synchronized
    fun clear() {
        entries.clear()
        characterCount = 0
    }
}
