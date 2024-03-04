package slak.ckompiler.backend.entities

import jakarta.persistence.*
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Table(name = "userstate")
@Entity
data class UserState(
    @Id val id: String,
    @OneToOne(optional = true) var autosaveViewState: ViewState?,
    @Column(columnDefinition = "text")
    var userName: String? = null,
)

@Repository
interface UserStateRepository : CrudRepository<UserState, String>
