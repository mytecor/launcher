package su.myt.home.model

data class AppGroup(
    val apps: MutableList<App>
) {
    val isGroup: Boolean
        get() = apps.size > 1

    val id: String
        get() = apps.map { it.id }.sorted().joinToString(";")
}
