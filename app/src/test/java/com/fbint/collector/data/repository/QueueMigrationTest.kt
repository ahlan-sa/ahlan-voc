package com.fbint.collector.data.repository

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.fbint.collector.data.local.AppDatabase
import com.fbint.collector.data.local.entity.QueuedFileEntity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class QueueMigrationTest {
    @Test fun upgradesKeepQueuedResponsesFromSchemaTwoThreeAndFour() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        for (version in listOf(2, 3, 4)) {
            val name = "migration-$version.db"
            context.deleteDatabase(name)
            val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name).callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        javaClass.classLoader!!.getResourceAsStream("legacy-schema4.sql")!!.bufferedReader().useLines { lines ->
                            lines.forEach { sql -> db.execSQL(sql.let { if (version <= 3) it.replace(", `sendingAt` INTEGER", "") else it }
                                .let { if (version == 2) it.replace(", `autoStampsJson` TEXT", "") else it }) }
                        }
                        db.execSQL("INSERT INTO queued_responses(clientUuid,surveyId,environmentId,finished,dataJson,capturedAt,attempts) VALUES('pending','survey','env',1,'{}',1,0)")
                        db.execSQL("INSERT INTO queued_files(clientUuid,surveyId,questionId,environmentId,localPath,fileName,mimeType,sizeBytes,capturedAt,attempts,boundResponseUuid) VALUES('file','survey','q','env','/test','test.txt','text/plain',5,1,0,'pending')")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                }).build())
            helper.writableDatabase
            helper.close()
            val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5).build()
            try {
                val response = db.responseQueueDao().getById("pending")!!
                assertNull(response.syncedAt)
                assertEquals("{}", response.dataJson)
                assertNull(response.serverBaseUrl)
                assertEquals("pending", db.queuedFileDao().getById("file")!!.boundResponseUuid)
            } finally { db.close(); context.deleteDatabase(name) }
        }
    }

    @Test fun fileClaimsAreAtomicAndUnfinishedAttachmentsStayLocal() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        try {
            val dao = db.queuedFileDao()
            dao.insert(QueuedFileEntity("file", "survey", "q", "env", "/test", "x.txt", "text/plain", 5, 1))
            assertTrue(dao.pendingOnce().isEmpty())
            dao.bindToResponse(listOf("file"), "response")
            assertEquals(1, dao.pendingOnce().size)
            val claims = List(8) { async(Dispatchers.IO) { dao.claimUpload("file", 1000, 0) } }.awaitAll()
            assertEquals(1, claims.sum())
        } finally { db.close() }
    }
}
