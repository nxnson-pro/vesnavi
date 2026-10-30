package com.nxnson.vesnavi.navcore

import java.util.UUID

/** A place the rider can route to in one tap. */
data class SavedPlace(
    val name: String,
    val subtitle: String? = null,
    val latitude: Double,
    val longitude: Double,
    val id: String = UUID.randomUUID().toString(),
) {
    val coordinate get() = Coordinate(latitude, longitude)

    /** Same spot (within ~30 m), to avoid duplicates in the lists. */
    fun isNear(other: SavedPlace) = Geo.distance(coordinate, other.coordinate) < 30
}

/** Where the scooter was left. */
data class ParkingSpot(
    val latitude: Double,
    val longitude: Double,
    /** Epoch millis. */
    val date: Long,
    /** GPS accuracy in metres. */
    val accuracy: Double? = null,
) {
    val coordinate get() = Coordinate(latitude, longitude)
}

/** Home, work, favourites, recent destinations and the parking spot. Every change returns a new value. */
data class Places(
    val home: SavedPlace? = null,
    val work: SavedPlace? = null,
    val favorites: List<SavedPlace> = emptyList(),
    val recents: List<SavedPlace> = emptyList(),
    val parking: ParkingSpot? = null,
) {
    fun isFavorite(p: SavedPlace) = favorites.any { it.isNear(p) }

    fun toggleFavorite(p: SavedPlace): Places =
        if (isFavorite(p)) copy(favorites = favorites.filterNot { it.isNear(p) }) else copy(favorites = favorites + p)

    fun addRecent(p: SavedPlace): Places = copy(recents = (listOf(p) + recents.filterNot { it.isNear(p) }).take(MAX_RECENTS))

    fun removeRecent(id: String) = copy(recents = recents.filterNot { it.id == id })

    fun removeFavorite(id: String) = copy(favorites = favorites.filterNot { it.id == id })

    companion object {
        const val MAX_RECENTS = 15
    }
}
