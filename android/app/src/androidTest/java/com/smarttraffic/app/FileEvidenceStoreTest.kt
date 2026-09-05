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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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

        assertEquals(record.id, persisted.id)
        assertEquals(record.id, loaded.id)
        assertNotNull(loaded.frameSha256)
        assertNotNull(loaded.vehicleCropSha256)
        val directory = context.getDir("traffic_evidence", android.content.Context.MODE_PRIVATE)
        val artifacts = directory.listFiles().orEmpty().filter { it.isFile }
        assertEquals(2, artifacts.size)
        assertArrayEquals(
            frame,
            artifacts.single { it.name.contains("_frame_") }.readBytes(),
        )
        assertArrayEquals(
            vehicle,
            artifacts.single { it.name.contains("_vehicle_") }.readBytes(),
        )
    }

    @Test
    fun retentionRemovesOldestRecordArtifacts() = runBlocking {
        repeat(6) { index ->
            store.save(
                record("event-$index", createdAtMs = index + 1L),
                EvidenceArtifacts(ByteArray(1024) { index.toByte() }, ByteArray(128) { index.toByte() }),
            )
        }

        val loaded = store.list()
        assertEquals(4, loaded.size)
        assertTrue(loaded.none { it.id == "event-0" })
        assertTrue(loaded.none { it.id == "event-1" })
        assertTrue(loaded.all { it.id.startsWith("event-") })
        val artifactNames = context.getDir("traffic_evidence", android.content.Context.MODE_PRIVATE)
            .listFiles().orEmpty().map { it.name }
        assertTrue(artifactNames.none { it.startsWith("event-0_") || it.startsWith("event-1_") })
    }

    @Test
    fun concurrentWritesRemainReadableAndConsistent() = runBlocking {
        coroutineScope {
            (2..8).map { index ->
                async {
                    store.save(
                        record("event-$index", createdAtMs = index + 1L),
                        EvidenceArtifacts(ByteArray(2048) { index.toByte() }, ByteArray(256) { (index * 2).toByte() }),
                    )
                }
            }.awaitAll()
        }

        val loaded = store.list()
        assertEquals(4, loaded.size)
        loaded.forEach { saved ->
            assertNotNull(saved.frameSha256)
            assertNotNull(saved.vehicleCropSha256)
        }
    }

    @Test(expected = IllegalStateException::class)
    fun tamperedArtifactIsRejectedOnRead() = runBlocking {
        store.save(record("event-tamper"), EvidenceArtifacts(ByteArray(64) { 1 }, ByteArray(64) { 2 }))
        val directory = context.getDir("traffic_evidence", android.content.Context.MODE_PRIVATE)
        val frame = directory.listFiles().orEmpty().single { it.name.contains("_frame_") }
        frame.writeBytes(ByteArray(64) { 9 })
        store.list()
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
