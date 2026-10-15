package pictologic.core

import java.util.Comparator
import java.util.regex.Pattern

/**
 * Natural order comparator for sorting filenames containing numbers.
 * Ensures "frame_2.png" comes before "frame_10.png".
 */
object NaturalSort : Comparator<String> {
    private val DIGIT_OR_NOT = Pattern.compile("(\\d+)|(\\D+)")

    override fun compare(s1: String?, s2: String?): Int {
        if (s1 == null && s2 == null) return 0
        if (s1 == null) return -1
        if (s2 == null) return 1

        val m1 = DIGIT_OR_NOT.matcher(s1)
        val m2 = DIGIT_OR_NOT.matcher(s2)

        while (m1.find()) {
            if (!m2.find()) return 1

            val chunk1 = m1.group()
            val chunk2 = m2.group()

            val isDigit1 = Character.isDigit(chunk1[0])
            val isDigit2 = Character.isDigit(chunk2[0])

            if (isDigit1 && isDigit2) {
                // Compare numeric chunks numerically
                // Strip leading zeros for comparison
                val b1 = chunk1.trimStart('0')
                val b2 = chunk2.trimStart('0')
                if (b1.length != b2.length) {
                    return b1.length - b2.length
                }
                val comp = b1.compareTo(b2)
                if (comp != 0) return comp
                // If equal numerically, compare raw lengths (leading zeros)
                val lenComp = chunk1.length - chunk2.length
                if (lenComp != 0) return lenComp
            } else {
                val comp = chunk1.compareTo(chunk2, ignoreCase = true)
                if (comp != 0) return comp
            }
        }

        return if (m2.find()) -1 else 0
    }
}
