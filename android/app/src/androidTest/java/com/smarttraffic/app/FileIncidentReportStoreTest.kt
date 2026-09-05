package com.smarttraffic.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smarttraffic.app.data.reports.FileIncidentReportStore
import com.smarttraffic.app.domain.analysis.IncidentReport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileIncidentReportStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var store: FileIncidentReportStore

    @Before
    fun setUp() {
        store = FileIncidentReportStore(context, maxRecords = 3)
    }

    @After
    fun tearDown() = runBlocking { store.clear() }

    @Test
    fun saveAndReloadPersistsReport() = runBlocking {
        val report = report("report-1", 1L)
        store.save(report)

        val loaded = store.list().single()
        assertEquals(report, loaded)
    }

    @Test
    fun retentionKeepsNewestReportsOnly() = runBlocking {
        repeat(5) { index -> store.save(report("report-$index", index + 1L)) }

        val loaded = store.list()
        assertEquals(3, loaded.size)
        assertTrue(loaded.none { it.id == "report-0" || it.id == "report-1" })
        assertEquals(listOf("report-4", "report-3", "report-2"), loaded.map { it.id })
    }

    private fun report(id: String, createdAtMs: Long) = IncidentReport(
        id = id,
        type = "Traffic violation",
        location = "Test location",
        plate = "123ABC",
        priority = "Normal",
        details = "Test report",
        createdAtMs = createdAtMs,
    )
}
