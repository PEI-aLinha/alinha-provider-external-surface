package pt.alinha.externalsurface.internal.web

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import pt.alinha.externalsurface.internal.AuthorizationRequired
import pt.alinha.externalsurface.internal.CollectionRunning
import pt.alinha.externalsurface.internal.DomainMissing
import pt.alinha.externalsurface.internal.InvalidInput
import pt.alinha.externalsurface.internal.NotActive
import pt.alinha.externalsurface.internal.TooSoon
import pt.alinha.externalsurface.internal.domain.HostNames

private const val CONCURRENT = "Os dados foram alterados noutro lado. Atualize a página."

/** RFC 9457 Problem Details with `code` and `errors` (ADR 0003). `detail` is shown to the user, in Portuguese. */
@RestControllerAdvice(basePackageClasses = [ExternalSurfaceController::class])
class ExternalSurfaceProblems {

    @ExceptionHandler
    fun invalid(e: InvalidInput) = validation(
        e.field,
        when (e.reason) {
            InvalidInput.Reason.NOT_A_HOST -> "Indique só o endereço, por exemplo loja.empresa.pt."
            InvalidInput.Reason.OUTSIDE_DOMAIN -> "Só pode juntar endereços do domínio da organização."
            InvalidInput.Reason.TOO_MANY -> "Pode juntar até ${HostNames.MAX_EXTRA} endereços."
        },
    )

    @ExceptionHandler
    fun authorization(e: AuthorizationRequired) = validation(
        "authorized", "Confirme que a organização é titular do domínio e autoriza a verificação.",
    ).apply { setProperty("code", "AUTHORIZATION_REQUIRED") }

    @ExceptionHandler
    fun domainMissing(e: DomainMissing) = problem(
        HttpStatus.UNPROCESSABLE_CONTENT, "DOMAIN_MISSING",
        "Indique primeiro o domínio da organização na página Organização.",
    )

    @ExceptionHandler
    fun unreadable(e: HttpMessageNotReadableException) =
        problem(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", "Não foi possível ler os dados enviados.")

    @ExceptionHandler
    fun notActive(e: NotActive) =
        problem(HttpStatus.NOT_FOUND, "NOT_ACTIVE", "A verificação do domínio não está ativa.")

    @ExceptionHandler
    fun running(e: CollectionRunning) =
        problem(HttpStatus.CONFLICT, "COLLECTION_RUNNING", "Já está a decorrer uma verificação. Aguarde que termine.")

    @ExceptionHandler
    fun tooSoon(e: TooSoon): ResponseEntity<ProblemDetail> = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, e.retryAfter.seconds.toString())
        .body(problem(HttpStatus.TOO_MANY_REQUESTS, "TOO_SOON", "A última verificação foi há pouco. Tente daqui a alguns minutos."))

    /** Two requests for the same organization at once (one row per tenant). */
    @ExceptionHandler
    fun duplicate(e: DataIntegrityViolationException) = problem(HttpStatus.CONFLICT, "CONCURRENT_CHANGE", CONCURRENT)

    @ExceptionHandler
    fun concurrent(e: ObjectOptimisticLockingFailureException) = problem(HttpStatus.CONFLICT, "CONCURRENT_CHANGE", CONCURRENT)

    private fun validation(field: String, message: String) =
        problem(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", "Há dados por corrigir.").apply {
            setProperty("errors", listOf(mapOf("field" to field, "message" to message)))
        }

    private fun problem(status: HttpStatus, code: String, detail: String) =
        ProblemDetail.forStatusAndDetail(status, detail).apply { setProperty("code", code) }
}
