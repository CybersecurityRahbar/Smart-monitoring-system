package com.smarttraffic.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smarttraffic.app.data.evidence.FileEvidenceStore
import com.smarttraffic.app.domain.analysis.EvidenceArtifacts
import com.smarttraffic.app.domain.analysis.EvidenceRecord
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileEvidenceStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var store: FileEvidenceStore

    @Before
    fun setUp() {
        store = FileEvidenceStore(context, maxRecords = 4, maxTotalArtifactBytes = 8 * 1024 * 1024)
    }

    @After
    fun tearDown() = runBlocking { store.clear() }

    @Test
    fun savePersistsMetadataArtifactsAndHashes() = runBlocking {
        val frame = ByteArray(1024) { (it and 0x7f).toByte() }
        val vehicle = ByteArray(256) { (255 - (it and 0xff)).toByte() }
        val record = record("event-1")

        val persisted = store.save(record, EvidenceArtifacts(frame, vehicle))
        val loaded = store.list().single()
        val artifactDir = context.getDir("traffic_evidence", android.content.Context.MODE_PRIVATE)

        assertEquals(record.id, persisted.id)
        assertEquals(record.id, loaded.id)
        assertNotNull(loaded.frameSha256)
        assertNotNull(loaded.vehicleCropSha256)
        assertEquals(2, artifactDir.listFiles().orEmpty().size)
        assertTrue(artifactDir.listFiles().orEmpty().all { it.name.endsWith(".jpg") })
    }

    @Test
    fun retentionRemovesOldestRecordArtifacts() = runBlocking {
        repeat(6) { index ->
            store.save(record("event-$index"), EvidenceArtifacts(ByteArray(1024) { index.toByte() }, ByteArray(128) { index.toByte() }))
        }

        val loaded = store.list()
        assertEquals(4, loaded.size)
        assertTrue(loaded.none { it.id == "event-0" })
        assertTrue(loaded.none { it.id == "event-1" })
        assertTrue(loaded.all { it.id.startsWith("event-") })
        assertEquals(8, context.getDir("traffic_evidence", android.content.Context.MODE_PRIVATE).listFiles().orEmpty().size)
    }

    @Test
    fun listFailsClosedWhenReferencedArtifactIsMissing() = runBlocking {
        store.save(record("event-corrupt"), EvidenceArtifacts(ByteArray(32) { 7 }, ByteArray(16) { 3 }))
        val hash = requireNotNull(store.list().single().frameSha256)
        val artifactDir = context.getDir("traffic_evidence", android.content.Context.MODE_PRIVATE)
        java.io.File(artifactDir, "${hash}_frame.jpg").delete()

        assertThrows(IllegalStateException::class.java) { runBlocking { store.list() } }
    }

    @Test
    fun concurrentSavesRemainConsistent() = runBlocking {
        coroutineScope {
            (0 until 8).map { index ->
                async {
                    store.save(
                        record("event-concurrent-$index", createdAtMs = 1000L + index),
                        EvidenceArtifacts(ByteArray(64) { index.toByte() }, ByteArray(32) { (index + 1).toByte() }),
                    )
                }
            }.awaitAll()
        }

        val loaded = store.list()
        assertEquals(4, loaded.size)
        assertTrue(loaded.all { it.frameSha256 != null && it.vehicleCropSha256 != null })
    }

    private fun record(id: String, createdAtMs: Long = System.currentTimeMillis()): EvidenceRecord = EvidenceRecord(
        id = id,
        eventId = id,
        sourceId = "test-source",
        sourceUri = "content://test/$id",
        frameIndex = 1L,
        timestampMs = 1000L,
        eventType = "SPEEDING",
        measuredSpeedKmh = 100.0,
        thresholdKmh = 80.0,
        confidence = 0.9f,
        trackId = 12L,
        detectorModel = "yolo26n",
        tracker = "bytetrack",
        createdAtMs = createdAtMs,
    )
}
