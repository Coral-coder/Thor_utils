package gg.thor.utils.core.roms

import java.io.File

/** One file movement, recorded so it can be reversed. */
data class JournalEntry(val from: String, val to: String)

data class UndoReport(
    val restored: Int,
    val missing: Int,
    val failed: List<String>,
) {
    val isClean: Boolean get() = failed.isEmpty() && missing == 0
}

/**
 * An append-only record of every file the sorter moved.
 *
 * This exists because a sort that cannot be reversed is not a tool, it is a
 * gamble. Each run writes where every file came from and where it went *before*
 * the next one is touched, so an interrupted or regretted run can always be put
 * back.
 *
 * The format is deliberately trivial — one `from\tto` pair per line, newest run
 * last — so it stays readable and repairable by hand with a text editor if this
 * code is ever the thing that is broken.
 */
class SortJournal(private val file: File) {

    /**
     * Records a move. Called immediately after each file lands, not batched at
     * the end: a run killed halfway through still leaves a usable record of
     * everything it had already done.
     */
    fun record(from: File, to: File) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText("${escape(from.absolutePath)}\t${escape(to.absolutePath)}\n")
        }
    }

    fun entries(): List<JournalEntry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 2 || parts.any { it.isBlank() }) null
                else JournalEntry(unescape(parts[0]), unescape(parts[1]))
            }
        }.getOrDefault(emptyList())
    }

    fun clear() {
        runCatching { file.delete() }
    }

    /**
     * Puts every recorded file back where it came from.
     *
     * Reversed order, so a file moved twice ends up at its original location.
     * A destination that no longer exists is counted as missing rather than
     * treated as an error — the user may already have moved it themselves — and
     * nothing is overwritten on the way back.
     */
    fun undo(): UndoReport {
        var restored = 0
        var missing = 0
        val failed = mutableListOf<String>()

        entries().reversed().forEach { entry ->
            val current = File(entry.to)
            val original = File(entry.from)
            if (!current.exists()) {
                missing++
                return@forEach
            }
            if (original.exists()) {
                // Something is already back at the source; leave both alone
                // rather than clobbering whichever one is real.
                failed += "${original.name}: a file is already at the original location"
                return@forEach
            }
            val result = runCatching {
                original.parentFile?.mkdirs()
                if (!current.renameTo(original)) {
                    current.copyTo(original, overwrite = false)
                    if (original.length() != current.length()) {
                        original.delete()
                        error("copy verification failed")
                    }
                    if (!current.delete()) error("restored but could not remove the moved copy")
                }
            }
            if (result.isSuccess) restored++ else failed += "${current.name}: ${result.exceptionOrNull()?.message}"
        }

        if (failed.isEmpty()) clear()
        return UndoReport(restored, missing, failed)
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
    private fun unescape(value: String) = value.replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\")
}
