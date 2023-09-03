package slak.ckompiler.backend.configuration

import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service

@Service
class WebSocketSecurityContextService {
  private val securityContexts = mutableMapOf<String, SecurityContext>()

  fun getOrCreateContext(sessionId: String): SecurityContext {
    val existing = securityContexts[sessionId]
    if (existing != null) {
      return existing
    }
    val newContext = SecurityContextHolder.createEmptyContext()
    securityContexts[sessionId] = newContext
    return newContext
  }

  fun deleteBySessionId(sessionId: String) {
    securityContexts.remove(sessionId)
  }
}
