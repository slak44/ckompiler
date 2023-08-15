package slak.ckompiler.backend.dto

import java.time.Instant

data class ViewStateListingDto(val id: String, val name: String, val createdAt: Instant, val publicShareEnabled: Boolean)
