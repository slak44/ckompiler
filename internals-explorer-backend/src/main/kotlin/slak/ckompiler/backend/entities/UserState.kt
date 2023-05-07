package slak.ckompiler.backend.entities

import jakarta.persistence.*
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Table(name = "userstate")
@Entity
data class UserState(
    @Id val id: String,
    @OneToOne var autosaveViewState: ViewState,
)

@Repository
interface UserStateRepository : CrudRepository<UserState, String>
