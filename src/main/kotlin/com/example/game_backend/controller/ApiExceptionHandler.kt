package com.example.game_backend.controller

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.net.ConnectException
import java.net.SocketTimeoutException

@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handleHandlerMethodValidationException(
        ex: HandlerMethodValidationException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val detail =
            ex.allErrors
                .mapNotNull { it.defaultMessage }
                .distinct()
                .joinToString("; ")
        return handleExceptionInternal(ex, ProblemDetail.forStatusAndDetail(status, detail), headers, status, request)
    }

    @ExceptionHandler(HttpRequestTimeoutException::class, ConnectTimeoutException::class, SocketTimeoutException::class)
    fun handleDownstreamTimeout(ex: Exception): ProblemDetail {
        log.warn("Downstream service timed out", ex)
        return ProblemDetail.forStatusAndDetail(HttpStatus.GATEWAY_TIMEOUT, "A downstream service timed out")
    }

    @ExceptionHandler(ResponseException::class, ConnectException::class)
    fun handleDownstreamFailure(ex: Exception): ProblemDetail {
        log.warn("Downstream service failed", ex)
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "A downstream service is unavailable")
    }
}
