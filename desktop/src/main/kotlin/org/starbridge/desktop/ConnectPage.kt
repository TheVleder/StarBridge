package org.starbridge.desktop

import org.starbridge.core.share.QrCode
import java.io.File

/**
 * A page on the computer with the QR the iPhone scans (the PC screen plays the role of the
 * Android screen): for Safari, and for the iPhone app StarBridge EAA (its URL scheme).
 */
class ConnectPage(private val file: File) {
    fun write(shareUrl: String, displayKey: String) {
        val appUrl = APP_SCHEME + shareUrl.substringAfter("://")
        val address = shareUrl.substringAfter("://").substringBefore("/")
        file.writeText(
            """
            <!doctype html>
            <html lang="es"><head><meta charset="utf-8"><title>StarBridge · conectar</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body { margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
                     background: #08090e; color: #f2f3f7; font: 16px/1.4 system-ui, -apple-system, "Segoe UI", sans-serif; }
              main { text-align: center; padding: 24px; }
              h1 { font-size: 22px; font-weight: 600; margin: 0 0 4px; }
              p { color: #8d93a7; margin: 0 0 22px; }
              .qrs { display: flex; gap: 28px; justify-content: center; flex-wrap: wrap; }
              figure { margin: 0; }
              .card { width: min(70vw, 300px); padding: 16px; border-radius: 26px; background: #f4f4f6; }
              .card svg { display: block; width: 100%; height: auto; }
              figcaption { margin-top: 10px; font-weight: 600; }
              figcaption small { display: block; color: #8d93a7; font-weight: 400; }
              .code { display: inline-block; margin-top: 24px; padding: 8px 16px; border-radius: 99px; background: #1a1d28;
                      font: 15px ui-monospace, Consolas, monospace; }
            </style></head>
            <body><main>
              <h1>StarBridge en el PC</h1>
              <p>Escanéalo con la cámara del iPhone (conectado a la misma WiFi)</p>
              <div class="qrs">
                <figure><div class="card">${QrCode.svg(shareUrl)}</div>
                  <figcaption>Navegador<small>se abre en Safari</small></figcaption></figure>
                <figure><div class="card">${QrCode.svg(appUrl)}</div>
                  <figcaption>App StarBridge EAA<small>si la tienes instalada</small></figcaption></figure>
              </div>
              <div class="code">$address · $displayKey</div>
            </main></body></html>
            """.trimIndent(),
        )
    }

    /** Shows the page in the computer's default browser. */
    fun open() {
        runCatching {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(file.toURI())
            } else {
                ProcessBuilder("cmd", "/c", "start", "", file.absolutePath).start()
            }
        }.onFailure { log("Abre a mano: ${file.absolutePath}") }
    }

    companion object {
        /** Same scheme as the Android screen's "QR para la app" (MainActivity). */
        const val APP_SCHEME = "starbridge-eaa://"
    }
}
