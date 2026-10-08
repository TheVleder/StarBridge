package org.starbridge.desktop

import java.io.File

/**
 * The computer's control panel in a window of its own: Edge (always present on Windows 10/11)
 * or Chrome in app mode, without tabs or address bar. Falls back to the default browser.
 */
object AppWindow {
    fun open(url: String) {
        val browser = findAppBrowser()
        val started = browser != null && runCatching {
            ProcessBuilder(browser.absolutePath, "--app=$url", "--window-size=1440,900", "--no-first-run")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.isSuccess
        if (started) return
        runCatching {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(java.net.URI(url))
            } else {
                ProcessBuilder("cmd", "/c", "start", "", url).start()
            }
        }.onFailure { log("Abre a mano en el navegador: $url") }
    }

    private fun findAppBrowser(): File? {
        val roots = listOf("ProgramFiles(x86)", "ProgramFiles", "LOCALAPPDATA").mapNotNull { System.getenv(it) }
        val candidates = roots.flatMap {
            listOf(
                File(it, "Microsoft/Edge/Application/msedge.exe"),
                File(it, "Google/Chrome/Application/chrome.exe"),
            )
        }
        return candidates.firstOrNull { it.isFile }
    }
}
