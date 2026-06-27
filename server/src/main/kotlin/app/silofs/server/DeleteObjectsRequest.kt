package app.silofs.server

data class DeleteObjectsRequest(
    val quiet: Boolean,
    val objects: List<Entry>,
) {
    data class Entry(
        val key: String,
    )
}
