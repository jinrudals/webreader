package app.webreader.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @Test
    fun v1RowsMoveToLegacySiteId() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        val file = ctx.getDatabasePath("migration-test.db")
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { raw ->
            raw.execSQL("CREATE TABLE `favorite` (`key` TEXT NOT NULL, `type` TEXT NOT NULL, `title` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))")
            raw.execSQL("CREATE TABLE `read_history` (`key` TEXT NOT NULL, `lastPosition` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))")
            raw.execSQL("INSERT INTO favorite VALUES ('/s/1', 'SERIES', 'T', 5)")
            raw.execSQL("INSERT INTO read_history VALUES ('/ep/1', 42, 6)")
            raw.version = 1
        }
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, "migration-test.db")
            .addMigrations(AppDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
        try {
            val fav = db.favoriteDao().observeAll("").first().single()
            assertEquals("/s/1", fav.key)
            assertEquals("T", fav.title)
            assertEquals(42, db.readHistoryDao().get("", "/ep/1")?.lastPosition)
            assertTrue(db.siteDao().observeAll().first().isEmpty())
        } finally {
            db.close()
        }
    }
}
