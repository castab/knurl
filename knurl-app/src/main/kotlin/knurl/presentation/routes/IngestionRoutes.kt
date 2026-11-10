package knurl.presentation.routes

import knurl.domain.repositories.IngestionJob
import knurl.domain.repositories.IngestionJobRepository
import knurl.domain.repositories.IngestionSchedule
import knurl.domain.repositories.IngestionWork
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
import org.http4k.contract.bindContract
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.format.KotlinxSerialization.autoBody
import org.http4k.lens.Path
import org.http4k.lens.Query
import org.http4k.lens.int
import kotlin.uuid.Uuid

@Serializable
data class ScheduleRequest(
    val enabled: Boolean,
    val intervalSeconds: Long = 900,
)

@Serializable
data class ScheduleResponse(
    val enabled: Boolean,
    val intervalSeconds: Long,
    val nextRunAt: String?,
)

@Serializable
data class JobResponse(
    val id: String,
    val instagramAccountId: String,
    val source: String,
    val status: String,
    val phase: String,
    val createdAt: String,
    val startedAt: String?,
    val completedAt: String?,
    val errorCode: String?,
    val attempts: Int,
    val succeeded: Int,
    val skipped: Int,
    val failed: Int,
    val pending: Int,
)

@Serializable
data class WorkResponse(
    val phase: String,
    val itemId: String,
    val status: String,
    val attempts: Int,
    val retryAt: String?,
    val errorCode: String?,
)

@Serializable
data class JobListResponse(
    val data: List<JobResponse>,
    val pagination: PaginationMeta,
)

@Serializable
data class JobDetailResponse(
    val job: JobResponse,
    val data: List<WorkResponse>,
    val pagination: PaginationMeta,
)

private fun IngestionSchedule.response() = ScheduleResponse(enabled, intervalSeconds, nextRunAt?.toString())

private fun IngestionJob.response() =
    JobResponse(
        id.toString(),
        instagramAccountId,
        source,
        status,
        phase,
        createdAt.toString(),
        startedAt?.toString(),
        completedAt?.toString(),
        errorCode,
        attempts,
        succeeded,
        skipped,
        failed,
        pending,
    )

private fun IngestionWork.response() = WorkResponse(phase, itemId, status, attempts, retryAt?.toString(), errorCode)

private val jobExample =
    JobResponse(
        "019a0000-0000-7000-8000-000000000001",
        "17841400000000000",
        "MANUAL",
        "PARTIAL",
        "PARTIAL",
        "2026-11-09T00:00:00Z",
        "2026-11-09T00:00:01Z",
        "2026-11-09T00:01:00Z",
        "ITEM_FAILURES",
        3,
        2,
        0,
        1,
        0,
    )

class IngestionRoutes(
    private val jobs: IngestionJobRepository,
    private val verifyAdminToken: VerifyAccountToken,
) {
    private val account = Path.of("accountId")
    private val job = Path.of("jobId")
    private val limit = Query.int().defaulted("limit", MAX_PAGE_SIZE)
    private val page = Query.int().defaulted("page", 1)
    private val scheduleInput = autoBody<ScheduleRequest>().toLens()
    private val scheduleOutput = autoBody<ScheduleResponse>().toLens()
    private val jobOutput = autoBody<JobResponse>().toLens()
    private val listOutput = autoBody<JobListResponse>().toLens()
    private val detailOutput = autoBody<JobDetailResponse>().toLens()
    private val errorOutput = autoBody<ErrorResponse>().toLens()
    private val schedulePath = "/api/v1/admin/accounts" / account / "ingestion" / "schedule"
    private val jobsPath = "/api/v1/admin/accounts" / account / "ingestion" / "jobs"

    private fun getSchedule(): ContractRoute =
        schedulePath meta {
            summary = "Read this account's ingestion schedule"
            security = adminBearerSecurity
            returning(Status.OK, scheduleOutput to ScheduleResponse(true, 900, "2030-01-01T00:00:00Z"))
        } bindContract Method.GET to
            { id, _, _ ->
                { request ->
                    authorizeAccount(id, request, verifyAdminToken) {
                        Response(Status.OK).with(scheduleOutput of jobs.schedule(id).response())
                    }
                }
            }

    private fun putSchedule(): ContractRoute =
        schedulePath meta {
            summary = "Enable or disable interval ingestion; minimum interval is 300 seconds"
            security = adminBearerSecurity
            receiving(scheduleInput to ScheduleRequest(true, 900))
            returning(Status.OK, scheduleOutput to ScheduleResponse(true, 900, "2030-01-01T00:00:00Z"))
        } bindContract Method.PUT to { id, _, _ ->
            { request ->
                authorizeAccount(id, request, verifyAdminToken) {
                    val input = scheduleInput(request)
                    Response(Status.OK).with(scheduleOutput of jobs.updateSchedule(id, input.enabled, input.intervalSeconds).response())
                }
            }
        }

    private fun enqueue(): ContractRoute =
        jobsPath meta {
            summary = "Queue ingestion or return this account's existing active job"
            security = adminBearerSecurity
            returning(Status.ACCEPTED, jobOutput to jobExample)
        } bindContract Method.POST to { id, _, _ ->
            { request ->
                authorizeAccount(id, request, verifyAdminToken) {
                    val result = jobs.enqueue(id)
                    Response(
                        Status.ACCEPTED,
                    ).header("Location", "/api/v1/admin/accounts/$id/ingestion/jobs/${result.id}").with(jobOutput of result.response())
                }
            }
        }

    private fun list(): ContractRoute =
        jobsPath meta {
            summary = "List this account's ingestion history"
            security = adminBearerSecurity
            queries += limit
            queries += page
            returning(
                Status.OK,
                listOutput to JobListResponse(listOf(jobExample), examplePagination("/api/v1/admin/accounts/{accountId}/ingestion/jobs")),
            )
        } bindContract Method.GET to { id, _, _ ->
            { request ->
                authorizeAccount(id, request, verifyAdminToken) {
                    val result = jobs.list(id, limit(request).coerceIn(1, MAX_PAGE_SIZE), page(request))
                    Response(
                        Status.OK,
                    ).with(listOutput of JobListResponse(result.items.map { it.response() }, paginationMeta(request, result)))
                }
            }
        }

    private fun get(): ContractRoute =
        jobsPath / job meta {
            summary = "Read job progress and a page of item outcomes"
            security = adminBearerSecurity
            queries += limit
            queries += page
            returning(
                Status.OK,
                detailOutput to
                    JobDetailResponse(
                        jobExample,
                        listOf(WorkResponse("DOWNLOAD", "example", "FAILED", 3, "2026-11-09T00:00:30Z", "MEDIA_NOT_READY")),
                        examplePagination("/api/v1/admin/accounts/{accountId}/ingestion/jobs/{jobId}"),
                    ),
            )
        } bindContract Method.GET to { id, _, _, rawJob ->
            { request ->
                authorizeAccount(id, request, verifyAdminToken) {
                    val parsed =
                        Uuid.parseOrNull(rawJob)
                            ?: return@authorizeAccount Response(Status.BAD_REQUEST).with(errorOutput of ErrorResponse("invalid job id"))
                    val result =
                        jobs.find(id, parsed)
                            ?: return@authorizeAccount Response(Status.NOT_FOUND).with(errorOutput of ErrorResponse("job not found"))
                    val work = jobs.workPage(parsed, limit(request).coerceIn(1, MAX_PAGE_SIZE), page(request))
                    Response(Status.OK).with(
                        detailOutput of
                            JobDetailResponse(
                                result.response(),
                                work.items.map {
                                    it.response()
                                },
                                paginationMeta(request, work),
                            ),
                    )
                }
            }
        }

    fun routes(): List<ContractRoute> = listOf(getSchedule(), putSchedule(), enqueue(), list(), get())
}
