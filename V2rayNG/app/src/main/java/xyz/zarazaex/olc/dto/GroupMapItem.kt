package xyz.zarazaex.olc.dto

data class GroupMapItem(
    var id: String,
    var remarks: String,
    var subIds: List<String> = listOf(id)
)