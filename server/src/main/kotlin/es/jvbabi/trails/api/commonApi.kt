package es.jvbabi.trails.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.engine.defaultExceptionStatusCode
import org.slf4j.LoggerFactory
import plus.vplan.commonapi.ApiException
import plus.vplan.commonapi.CommonApiPlugin
import plus.vplan.commonapi.logger.Emission
import plus.vplan.commonapi.logger.LoggingPlugin
import plus.vplan.commonapi.logger.loggerJson

const val API_PREFIX = "/api/v1"

/** Request headers that carry credentials and must never end up in a log. */
private val sensitiveHeaders = setOf("Authorization", "Cookie", "Set-Cookie")

/**
 * Installs commonapi: the `?api_info` page, `/api/v1/healthcheck`, the HTML 404 fallback
 * under [API_PREFIX], the error pages and the per-call `call.logger`.
 *
 * [CommonApiPlugin] installs `StatusPages` itself, so it must not be installed a second
 * time. Errors with a status other than 500 are thrown as [ApiException]; anything else
 * becomes a 500.
 */
fun Application.installCommonApi() {
    install(CommonApiPlugin) {
        serviceName = "trails"
        apiPrefix = API_PREFIX
        printStackTraces = false
        defaultText { +"This is the API of Trails." }
        healthCheck { "OK" }
    }

    install(LoggingPlugin) {
        val requestLogger = LoggerFactory.getLogger("Request")
        onLogEmission = { log ->
            val emission = log.toEmission().let { emission ->
                emission.copy(
                    request = emission.request.copy(
                        headers = emission.request.headers.mapValues { (name, value) ->
                            if (sensitiveHeaders.any { it.equals(name, ignoreCase = true) }) "<redacted>" else value
                        }
                    )
                )
            }
            val message = loggerJson.encodeToString(Emission.serializer(), emission)
            if (log.error != null) requestLogger.error(message) else requestLogger.info(message)
        }
    }

    // The StatusPages of CommonApiPlugin turns every exception that isn't an ApiException
    // into a 500. Ktor's own exceptions (e.g. a BadRequestException from call.receive())
    // keep the status Ktor would have answered with.
    intercept(ApplicationCallPipeline.Plugins) {
        try {
            proceed()
        } catch (cause: Throwable) {
            val status = defaultExceptionStatusCode(cause) ?: throw cause
            throw KtorApiException(status, cause.message ?: status.description, cause)
        }
    }
}

private class KtorApiException(
    override val status: HttpStatusCode,
    message: String,
    cause: Throwable,
) : ApiException(message) {
    init {
        initCause(cause)
    }
}
