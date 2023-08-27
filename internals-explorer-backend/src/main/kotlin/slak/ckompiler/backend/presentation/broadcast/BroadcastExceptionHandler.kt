package slak.ckompiler.backend.presentation.broadcast

import org.slf4j.LoggerFactory
import org.springframework.messaging.handler.annotation.Headers
import org.springframework.messaging.handler.annotation.MessageExceptionHandler
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.web.bind.annotation.ControllerAdvice

@ControllerAdvice
class BroadcastExceptionHandler {
  @MessageExceptionHandler
  fun messageExceptionHandler(e: Exception, @Headers headers: Map<String, Any>, @Payload payload: ByteArray) {
    logger.error(headers.toString())
    logger.error(payload.decodeToString())
    logger.error(e.stackTraceToString())
  }

  companion object {
    private val logger = LoggerFactory.getLogger(BroadcastExceptionHandler::class.java)
  }
}

