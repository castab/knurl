package knurl.app.ingestion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knurl.ingestion.client.MetaGraphApiException
import knurl.ingestion.pipeline.MediaNotReadyException
import java.io.IOException

class RetryPolicyTest :
    FunSpec({
        test("credential rejection is terminal, rate limiting and server failures retry") {
            retryable(MetaGraphApiException(401, "a token that must not leak")) shouldBe false
            retryable(MetaGraphApiException(403)) shouldBe false
            retryable(MetaGraphApiException(404)) shouldBe false
            retryable(MetaGraphApiException(429)) shouldBe true
            retryable(MetaGraphApiException(503)) shouldBe true
            retryable(IOException("temporary network failure")) shouldBe true
            retryable(MediaNotReadyException()) shouldBe true
            retryable(knurl.ingestion.processor.MediaHttpException(404)) shouldBe false
            retryable(knurl.ingestion.processor.MediaHttpException(503)) shouldBe true
            retryable(knurl.ingestion.processor.SourceMediaLimitException("too large")) shouldBe false
            retryable(SuspiciousFeedException()) shouldBe false
        }
        test("backoff honors bounded Retry-After without shortening the base delay") {
            retryDelaySeconds(1, null) shouldBe 5
            retryDelaySeconds(2, null) shouldBe 30
            retryDelaySeconds(1, 60) shouldBe 60
            retryDelaySeconds(2, 1) shouldBe 30
            retryDelaySeconds(1, 99999) shouldBe 300
        }
        test("API exceptions and job failure codes redact upstream response bodies") {
            val error = MetaGraphApiException(401, "secret-access-token")
            error.message shouldBe "Graph API request failed (401)"
            failureCode(error) shouldBe "INSTAGRAM_CREDENTIAL_REJECTED"
        }
    })
