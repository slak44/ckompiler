package slak.ckompiler.backend.entities

import jakarta.persistence.*
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import slak.ckompiler.backend.dto.GraphViewStateDto
import slak.ckompiler.backend.dto.ViewStateDto
import java.time.Instant
import java.util.*

@Embeddable
@Suppress("JpaAttributeMemberSignatureInspection")
data class ZoomTransform(
    val k: Double,
    val x: Double,
    val y: Double,
)

@Embeddable
@Suppress("JpaAttributeMemberSignatureInspection")
data class GraphViewState(
    val isUiHidden: Boolean,
    val isSpillOnly: Boolean,
    val targetFunction: String,
    val printingType: String,
    val transform: ZoomTransform,
    val selectedNodeId: Int?,
) {
  constructor(dto: GraphViewStateDto) : this(
      dto.isUiHidden,
      dto.isSpillOnly,
      dto.targetFunction,
      dto.printingType,
      dto.transform,
      dto.selectedNodeId,
  )
}

@Table(name = "viewstate")
@Entity
data class ViewState(
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null,
    val createdAt: Instant,
    val owner: String,
    val name: String,
    val sourceCode: String,
    val isaType: String,
    val activeRoute: String,
    @Embedded val graphViewState: GraphViewState,
) {
  constructor(dto: ViewStateDto) : this(
      dto.id?.let(UUID::fromString),
      Instant.now(),
      dto.owner!!,
      dto.name,
      dto.sourceCode,
      dto.isaType,
      dto.activeRoute,
      GraphViewState(dto.graphViewState),
  )
}

@Repository
interface ViewStateRepository : CrudRepository<ViewState, UUID> {
  fun findAllByOwner(owner: String): List<ViewState>
}
