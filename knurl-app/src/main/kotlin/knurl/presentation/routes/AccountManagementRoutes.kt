package knurl.presentation.routes

import knurl.domain.config.S3Settings
import knurl.domain.models.AccountChange
import knurl.domain.models.AccountRegistration
import knurl.domain.models.AccountSummary
import knurl.domain.repositories.AccountRepository
import knurl.presentation.auth.BearerToken
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
import org.http4k.contract.bindContract
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Filter
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.format.KotlinxSerialization.autoBody
import org.http4k.lens.Path
import org.http4k.lens.Query
import org.http4k.lens.int
import org.http4k.security.BearerAuthSecurity
import java.time.Instant

@Serializable
class StorageRequest(
    val bucketName: String,
    val region: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val endpoint: String? = null,
    val pathStyleAccess: Boolean = false,
) {
    fun settings() = S3Settings(bucketName, region, endpoint, accessKeyId, secretAccessKey, pathStyleAccess)
}

@Serializable
class RegisterAccountRequest(
    val instagramAccountId: String,
    val readToken: String,
    val adminToken: String,
    val instagramAccessToken: String,
    val instagramTokenExpiresAt: String,
    val storage: StorageRequest,
)

@Serializable
class UpdateAccountRequest(
    val enabled: Boolean? = null,
    val readToken: String? = null,
    val adminToken: String? = null,
    val instagramAccessToken: String? = null,
    val instagramTokenExpiresAt: String? = null,
    val storage: StorageRequest? = null,
)

@Serializable
data class StorageResponse(
    val bucketName: String,
    val region: String,
    val endpoint: String?,
    val accessKeyId: String,
    val pathStyleAccess: Boolean,
    val hasSecretAccessKey: Boolean = true,
)

@Serializable
data class ManagedAccountResponse(
    val instagramAccountId: String,
    val enabled: Boolean,
    val readToken: String,
    val hasAdminToken: Boolean,
    val hasInstagramAccessToken: Boolean,
    val instagramTokenExpiresAt: String,
    val storage: StorageResponse,
    val scheduleEnabled: Boolean,
    val intervalSeconds: Long,
    val nextRunAt: String?,
    val createdAt: String,
)

@Serializable
data class ManagedAccountListResponse(
    val data: List<ManagedAccountResponse>,
    val pagination: PaginationMeta,
)

internal fun AccountSummary.response() =
    ManagedAccountResponse(
        instagramAccountId,
        enabled,
        readToken,
        true,
        true,
        instagramTokenExpiresAt.toString(),
        StorageResponse(bucketName, region, endpoint, accessKeyId, pathStyleAccess),
        scheduleEnabled,
        intervalSeconds,
        nextRunAt?.toString(),
        createdAt.toString(),
    )

private fun parseExpiry(value: String): Instant =
    try {
        Instant.parse(value)
    } catch (_: java.time.format.DateTimeParseException) {
        throw IllegalArgumentException("invalid credential expiry")
    }

internal val managementBearerSecurity = BearerAuthSecurity(Filter { next -> next }, "deploymentManagementBearer")
internal val accountExample =
    ManagedAccountResponse(
        "17841400000000000",
        true,
        "example-read-token",
        true,
        true,
        "2030-01-01T00:00:00Z",
        StorageResponse("account-media", "us-east-1", "https://s3.example.com", "example-access-key", false),
        false,
        900,
        "2030-01-01T00:00:00Z",
        "2026-11-09T00:00:00Z",
    )
private val storageExample =
    StorageRequest("account-media", "us-east-1", "example-access-key", "example-secret-key", "https://s3.example.com", false)

class AccountManagementRoutes(
    private val accounts: AccountRepository,
    private val managementSecret: () -> String,
    private val update: (String, AccountChange) -> AccountSummary = accounts::update,
) {
    private val idPath = Path.of("accountId")
    private val limit = Query.int().defaulted("limit", MAX_PAGE_SIZE)
    private val page = Query.int().defaulted("page", 1)
    private val registerLens = autoBody<RegisterAccountRequest>().toLens()
    private val updateLens = autoBody<UpdateAccountRequest>().toLens()
    private val responseLens = autoBody<ManagedAccountResponse>().toLens()
    private val listLens = autoBody<ManagedAccountListResponse>().toLens()
    private val errorLens = autoBody<ErrorResponse>().toLens()

    private fun authorize(
        request: Request,
        action: () -> Response,
    ): Response =
        if (BearerToken.matches(BearerToken.extract(request), managementSecret())) {
            action()
        } else {
            Response(Status.UNAUTHORIZED).with(errorLens of ErrorResponse("unauthorized"))
        }

    private fun register(): ContractRoute =
        "/api/v1/management/accounts" meta {
            summary = "Register an account with encrypted credentials and account-owned S3 storage"
            security = managementBearerSecurity
            receiving(
                registerLens to
                    RegisterAccountRequest(
                        "17841400000000000",
                        "example-read-token",
                        "example-admin-token",
                        "example-instagram-token",
                        "2030-01-01T00:00:00Z",
                        storageExample,
                    ),
            )
            returning(Status.CREATED, responseLens to accountExample)
        } bindContract Method.POST to { request ->
            authorize(request) {
                val input = registerLens(request)
                val result =
                    accounts.register(
                        AccountRegistration(
                            input.instagramAccountId,
                            input.readToken,
                            input.adminToken,
                            input.instagramAccessToken,
                            parseExpiry(input.instagramTokenExpiresAt),
                            input.storage.settings(),
                        ),
                    )
                Response(
                    Status.CREATED,
                ).header("Location", "/api/v1/management/accounts/${result.instagramAccountId}").with(responseLens of result.response())
            }
        }

    private fun list(): ContractRoute =
        "/api/v1/management/accounts" meta {
            summary = "List registered accounts without disclosing secret credentials"
            security = managementBearerSecurity
            queries += limit
            queries += page
            returning(
                Status.OK,
                listLens to ManagedAccountListResponse(listOf(accountExample), examplePagination("/api/v1/management/accounts")),
            )
        } bindContract Method.GET to { request ->
            authorize(request) {
                val result = accounts.list(limit(request).coerceIn(1, MAX_PAGE_SIZE), page(request))
                Response(
                    Status.OK,
                ).with(listLens of ManagedAccountListResponse(result.items.map { it.response() }, paginationMeta(request, result)))
            }
        }

    private fun get(): ContractRoute =
        "/api/v1/management/accounts" / idPath meta {
            summary = "Inspect account configuration; secrets remain write-only"
            security = managementBearerSecurity
            returning(Status.OK, responseLens to accountExample)
        } bindContract Method.GET to { id ->
            { request ->
                authorize(request) {
                    accounts.find(id)?.let { Response(Status.OK).with(responseLens of it.response()) }
                        ?: Response(Status.NOT_FOUND).with(errorLens of ErrorResponse("unknown account"))
                }
            }
        }

    private fun patch(): ContractRoute =
        "/api/v1/management/accounts" / idPath meta {
            summary = "Replace account credentials, configure storage, or enable/disable an account"
            description =
                "Instagram token and expiry must be replaced together. Storage replacement requires the complete configuration, including its secret. Destination changes are rejected while storage is in use."
            security = managementBearerSecurity
            receiving(
                updateLens to
                    UpdateAccountRequest(
                        true,
                        "replacement-read-token",
                        "replacement-admin-token",
                        "replacement-instagram-token",
                        "2030-01-01T00:00:00Z",
                        storageExample,
                    ),
            )
            returning(Status.OK, responseLens to accountExample)
        } bindContract Method.PATCH to { id ->
            { request ->
                authorize(request) {
                    val input = updateLens(request)
                    val change =
                        AccountChange(
                            input.enabled,
                            input.readToken,
                            input.adminToken,
                            input.instagramAccessToken,
                            input.instagramTokenExpiresAt?.let(::parseExpiry),
                            input.storage?.settings(),
                        )
                    Response(Status.OK).with(responseLens of update(id, change).response())
                }
            }
        }

    fun routes(): List<ContractRoute> = listOf(register(), list(), get(), patch())
}
