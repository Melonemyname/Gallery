package com.melone.gallery.data.settings

/** Ein ausgewählter Server-Root inkl. auszuschließender Unterpfade. */
data class ServerFolder(
    val share: String,
    /** Relativer Pfad ab Freigabe-Wurzel ("" = ganze Freigabe). */
    val rootPath: String = "",
    /** Relative Pfade (ab Freigabe-Wurzel), die übersprungen werden. */
    val excludes: List<String> = emptyList(),
    val recursive: Boolean = true,
) {
    val label: String get() = if (rootPath.isEmpty()) share else "$share/$rootPath"
}

/** Nicht-geheime Serverkonfiguration (Passwort liegt in SecureCredentials). */
data class ServerConfig(
    val host: String = "",
    val username: String = "",
    val folders: List<ServerFolder> = emptyList(),
) {
    val isConfigured: Boolean get() = host.isNotBlank() && username.isNotBlank()
}
