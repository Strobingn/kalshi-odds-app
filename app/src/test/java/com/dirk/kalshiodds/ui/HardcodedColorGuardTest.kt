package com.dirk.kalshiodds.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Composables outside `ui/theme` must not import the dark-only Color.kt
 * tokens or hard-code Color(0x…) / Color.White / Color.Black for text.
 * That was the light-mode bug: [com.dirk.kalshiodds.ui.theme.Bg] +
 * Material `onBackground` in light scheme.
 */
class HardcodedColorGuardTest {

    private val bannedImports = listOf(
        "import com.dirk.kalshiodds.ui.theme.Bg",
        "import com.dirk.kalshiodds.ui.theme.Surface",
        "import com.dirk.kalshiodds.ui.theme.SurfaceAlt",
        "import com.dirk.kalshiodds.ui.theme.TextPrimary",
        "import com.dirk.kalshiodds.ui.theme.TextSecondary",
        "import com.dirk.kalshiodds.ui.theme.AccentBlue",
        "import com.dirk.kalshiodds.ui.theme.AccentGreen",
        "import com.dirk.kalshiodds.ui.theme.AccentOrange",
        "import com.dirk.kalshiodds.ui.theme.AccentRed"
    )

    @Test
    fun composablesDoNotImportDarkOnlyColorTokens() {
        val files = uiComposableFiles()
        assertTrue("no UI composable files found", files.isNotEmpty())
        val hits = mutableListOf<String>()
        for (f in files) {
            val text = f.readText()
            for (line in bannedImports) {
                if (text.contains(line)) hits += "${rel(f)}: $line"
            }
            if (Regex("""Color\(0x""").containsMatchIn(text)) {
                hits += "${rel(f)}: Color(0x…)"
            }
            if (Regex("""color\s*=\s*Color\.(White|Black)""").containsMatchIn(text)) {
                hits += "${rel(f)}: Color.White/Black as text"
            }
        }
        assertTrue("hard-coded colors outside theme:\n${hits.joinToString("\n")}", hits.isEmpty())
    }

    private fun uiComposableFiles(): List<File> {
        val roots = listOf(
            File("src/main/java/com/dirk/kalshiodds/ui"),
            File("app/src/main/java/com/dirk/kalshiodds/ui"),
            File("../app/src/main/java/com/dirk/kalshiodds/ui")
        )
        val root = roots.firstOrNull { it.isDirectory } ?: return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { "theme" !in it.toPath().map { p -> p.toString() } }
            .filter { !it.name.endsWith("ViewModel.kt") }
            .toList()
    }

    private fun rel(f: File): String = f.path.substringAfter("kalshiodds/ui/")
}
