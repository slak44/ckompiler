package slak.ckompiler.backend.entities

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import slak.ckompiler.backend.dto.GraphViewStateDto
import slak.ckompiler.backend.dto.SteppableGraphViewStateDto
import slak.ckompiler.backend.dto.ViewStateDto
import java.time.Instant
import java.util.*

@Embeddable
data class ZoomTransform(
    val k: Double?,
    val x: Double?,
    val y: Double?,
)

@Embeddable
data class GraphViewState(
    val isUiHidden: Boolean,
    val isSpillOnly: Boolean,
    @Column(columnDefinition = "text not null", nullable = false)
    val targetFunction: String,
    @Column(columnDefinition = "text not null", nullable = false)
    val printingType: String,
    val transform: ZoomTransform?,
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

@Embeddable
data class SteppableGraphViewState(
    val targetVariable: Int?,
    val transform: ZoomTransform?,
    val selectedNodeId: Int?,
    val currentStep: Int,
) {
  constructor(dto: SteppableGraphViewStateDto) : this(
      dto.targetVariable,
      dto.transform,
      dto.selectedNodeId,
      dto.currentStep
  )
}

@Table(name = "viewstate")
@Entity
data class ViewState(
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    val createdAt: Instant,
    var publicShareEnabled: Boolean = false,
    @Column(columnDefinition = "text not null", nullable = false)
    val owner: String,
    @Column(columnDefinition = "text not null", nullable = false)
    val name: String,
    @Column(columnDefinition = "text not null", nullable = false)
    val sourceCode: String,
    @Column(columnDefinition = "text not null", nullable = false)
    val isaType: String,
    @Column(columnDefinition = "text not null", nullable = false)
    val activeRoute: String,
    @Embedded
    val graphViewState: GraphViewState,
    @Embedded
    val phiInsertionViewState: SteppableGraphViewState,
    @Embedded
    val variableRenameViewState: SteppableGraphViewState,
) {
  constructor(dto: ViewStateDto) : this(
      dto.id?.let(UUID::fromString),
      Instant.now(),
      dto.publicShareEnabled,
      dto.owner!!,
      dto.name,
      dto.sourceCode,
      dto.isaType,
      dto.activeRoute,
      GraphViewState(dto.graphViewState),
      SteppableGraphViewState(dto.phiInsertionViewState),
      SteppableGraphViewState(dto.variableRenameViewState),
  )
}

@Repository
interface ViewStateRepository : JpaRepository<ViewState, UUID>, CrudRepository<ViewState, UUID> {
  fun findAllByOwner(owner: String): List<ViewState>
}
