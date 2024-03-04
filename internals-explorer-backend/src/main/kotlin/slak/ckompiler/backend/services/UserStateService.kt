package slak.ckompiler.backend.services

import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import slak.ckompiler.backend.dto.UserStateDto
import slak.ckompiler.backend.entities.UserState
import slak.ckompiler.backend.entities.UserStateRepository
import slak.ckompiler.backend.entities.ViewState
import slak.ckompiler.backend.entities.ViewStateRepository

@Service
class UserStateService(
    val userStateRepository: UserStateRepository,
    val viewStateRepository: ViewStateRepository,
    @Value("\${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") oauthIssuerUri: String,
) {
  private val auth0Client = WebClient.builder()
      .baseUrl(oauthIssuerUri)
      .filters {
        it += ExchangeFilterFunction.ofResponseProcessor { response ->
          logger.info("Response from ${oauthIssuerUri}: ${response.statusCode()}")
          return@ofResponseProcessor Mono.just(response);
        }
      }
      .build()

  private fun fetchUserInfo(token: JwtAuthenticationToken): Map<String, JsonNode> {
    val rawAuthToken = token.token.tokenValue

    return auth0Client.get()
        .uri("/userinfo")
        .header(HttpHeaders.AUTHORIZATION, "Bearer $rawAuthToken")
        .accept(MediaType.APPLICATION_JSON)
        .retrieve()
        .bodyToMono<Map<String, JsonNode>>()
        .block() ?: emptyMap()
  }

  private fun fetchUserName(token: JwtAuthenticationToken): String? {
    val userInfo = fetchUserInfo(token)
    val name = userInfo["name"] ?: userInfo["nickname"] ?: userInfo["given_name"] ?: userInfo["preferred_username"]
    return name?.textValue()
  }

  private fun findEntityById(id: String, token: JwtAuthenticationToken): UserState {
    return userStateRepository.findById(id).map {
      if (it.userName == null) {
        it.copy(userName = fetchUserName(token))
      } else {
        it
      }
    }.orElseGet {
      userStateRepository.save(UserState(id, null, fetchUserName(token)))
    }
  }

  fun findById(id: String, token: JwtAuthenticationToken): UserStateDto {
    return UserStateDto(findEntityById(id, token))
  }

  fun findNameById(id: String): String {
    return userStateRepository.findById(id).map(UserState::userName).orElseThrow() ?: ""
  }

  fun markAutosaveViewState(viewState: ViewState, token: JwtAuthenticationToken) {
    require(viewState.owner == token.name)
    val userState = findEntityById(viewState.owner, token)

    val oldAutosave = userState.autosaveViewState
    userState.autosaveViewState = viewState
    userStateRepository.save(userState)
    oldAutosave?.let { viewStateRepository.delete(it) }
  }

  companion object {
    private val logger = LoggerFactory.getLogger(UserStateService::class.java)
  }
}
