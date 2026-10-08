package org.starbridge.desktop

import org.starbridge.core.server.SettingsStore
import java.io.File
import java.util.Properties

/** [SettingsStore] in a properties file (access key, slack, site…): survives restarts. */
class FileSettings(private val file: File) : SettingsStore {
    private val props = Properties()

    init {
        if (file.isFile) file.inputStream().use { props.load(it) }
    }

    @Synchronized
    override fun get(key: String): String? = props.getProperty(key)

    @Synchronized
    override fun put(key: String, value: String) {
        props.setProperty(key, value)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { props.store(it, "StarBridge (PC)") }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
