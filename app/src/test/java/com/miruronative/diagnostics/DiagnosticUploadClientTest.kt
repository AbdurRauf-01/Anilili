package com.miruronative.diagnostics

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class DiagnosticUploadClientTest {
    private lateinit var server: MockWebServer
    private lateinit var report: File

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        report = File.createTempFile("diagnostic-upload", ".zip").apply {
            writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1, 2, 3))
        }
    }

    @After
    fun tearDown() {
        report.delete()
        server.shutdown()
    }

    @Test
    fun uploadSendsMultipartReportAndAcceptsMatchingReceipt() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"accepted","reportId":"ANL-20260730-ABC123DE45","receivedBytes":7}"""),
        )
        val client = DiagnosticUploadClient(server.url("/").toString(), OkHttpClient())

        val receipt = client.upload(report, "ANL-20260730-ABC123DE45", DiagnosticTrigger.CRASH)

        assertEquals("ANL-20260730-ABC123DE45", receipt.reportId)
        assertEquals(7L, receipt.receivedBytes)
        val request = server.takeRequest()
        assertEquals("/v1/reports", request.path)
        val multipart = request.body.readUtf8()
        assertTrue(multipart.contains("name=\"report_id\""))
        assertTrue(multipart.contains("ANL-20260730-ABC123DE45"))
        assertTrue(multipart.contains("name=\"trigger\""))
        assertTrue(multipart.contains("crash"))
        assertTrue(multipart.contains("filename=\"ANL-20260730-ABC123DE45.zip\""))
    }

    @Test
    fun serverFailureIsMarkedRetryable() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val client = DiagnosticUploadClient(server.url("/").toString(), OkHttpClient())

        val error = runCatching {
            client.upload(report, "ANL-20260730-ABC123DE45", DiagnosticTrigger.MANUAL)
        }.exceptionOrNull() as DiagnosticUploadHttpException

        assertEquals(503, error.statusCode)
        assertTrue(error.retryable)
    }

    @Test
    fun invalidReceiptIsRejected() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"accepted","reportId":"ANL-20260730-WRONG00000","receivedBytes":7}"""),
        )
        val client = DiagnosticUploadClient(server.url("/").toString(), OkHttpClient())

        val error = runCatching {
            client.upload(report, "ANL-20260730-ABC123DE45", DiagnosticTrigger.SHORTCUT)
        }.exceptionOrNull()

        assertTrue(error is java.io.IOException)
    }

    @Test
    fun blankEndpointFailsWithoutMakingARequest() = runBlocking {
        val client = DiagnosticUploadClient("", OkHttpClient())

        val error = runCatching {
            client.upload(report, "ANL-20260730-ABC123DE45", DiagnosticTrigger.MANUAL)
        }.exceptionOrNull()

        assertTrue(error is DiagnosticUploadUnavailableException)
        assertEquals(0, server.requestCount)
    }
}
