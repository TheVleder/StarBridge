package org.starbridge.desktop

import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.util.Locale
import javax.imageio.ImageIO

/**
 * The StarBridge icon by the clock (Windows notification area, macOS menu bar): the program has
 * no console window when started by double-click, so this is how to open the panel again and
 * how to quit (which stops the telescope first).
 */
object Tray {
    fun install(onOpen: () -> Unit, onQr: () -> Unit, onQuit: () -> Unit) {
        if (!SystemTray.isSupported()) return
        val es = Locale.getDefault().language == "es"
        runCatching {
            val image = Tray::class.java.classLoader.getResourceAsStream("web/icon-192.png")?.use { ImageIO.read(it) }
                ?: Toolkit.getDefaultToolkit().createImage(ByteArray(0))
            val menu = PopupMenu().apply {
                add(MenuItem(if (es) "Abrir StarBridge" else "Open StarBridge").apply { addActionListener { onOpen() } })
                add(MenuItem(if (es) "QR para el iPhone" else "QR for the iPhone").apply { addActionListener { onQr() } })
                addSeparator()
                add(MenuItem(if (es) "Salir (para el telescopio)" else "Quit (stops the telescope)").apply { addActionListener { onQuit() } })
            }
            val icon = TrayIcon(image, "StarBridge", menu).apply {
                isImageAutoSize = true
                addActionListener { onOpen() } // double-click
            }
            SystemTray.getSystemTray().add(icon)
            icon.displayMessage(
                "StarBridge",
                if (es) "Funcionando junto al reloj. Ciérralo desde aquí: para el telescopio." else "Running by the clock. Quit it from here: it stops the telescope.",
                TrayIcon.MessageType.INFO,
            )
        }
    }
}
