package app.webreader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Favorite::class, ReadHistory::class, SiteEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun readHistoryDao(): ReadHistoryDao
    abstract fun siteDao(): SiteDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `site` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`baseUrl` TEXT NOT NULL, `aliasHosts` TEXT NOT NULL, `seriesPattern` TEXT NOT NULL, " +
                        "`episodePattern` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE `favorite_new` (`siteId` TEXT NOT NULL, `key` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`siteId`, `key`))",
                )
                db.execSQL("INSERT INTO `favorite_new` SELECT '', `key`, `type`, `title`, `addedAt` FROM `favorite`")
                db.execSQL("DROP TABLE `favorite`")
                db.execSQL("ALTER TABLE `favorite_new` RENAME TO `favorite`")
                db.execSQL(
                    "CREATE TABLE `read_history_new` (`siteId` TEXT NOT NULL, `key` TEXT NOT NULL, " +
                        "`lastPosition` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`siteId`, `key`))",
                )
                db.execSQL("INSERT INTO `read_history_new` SELECT '', `key`, `lastPosition`, `updatedAt` FROM `read_history`")
                db.execSQL("DROP TABLE `read_history`")
                db.execSQL("ALTER TABLE `read_history_new` RENAME TO `read_history`")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "webreader.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
