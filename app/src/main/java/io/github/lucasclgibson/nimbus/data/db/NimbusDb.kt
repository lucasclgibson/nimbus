package io.github.lucasclgibson.nimbus.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** v2: syncStamp on catalogue tables (streamed refreshes). Keeps favourites, history and playlists. */
val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        for (table in listOf("channels", "movies", "series", "episodes")) {
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `syncStamp` INTEGER NOT NULL DEFAULT 0")
        }
    }
}

/** v3: index for guide -> channel lookups (search "On TV"). */
val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_playlistId_epgId` ON `channels` (`playlistId`, `epgId`)")
    }
}

/** v4: "remove from Continue watching" hides history entries without forgetting them. */
val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `history` ADD COLUMN `dismissed` INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(
    entities = [
        PlaylistEntity::class, CategoryEntity::class, ChannelEntity::class, ProgrammeEntity::class,
        MovieEntity::class, SeriesEntity::class, EpisodeEntity::class, FavouriteEntity::class,
        HistoryEntity::class, DetailCacheEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class NimbusDb : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
    abstract fun categories(): CategoryDao
    abstract fun channels(): ChannelDao
    abstract fun programmes(): ProgrammeDao
    abstract fun movies(): MovieDao
    abstract fun series(): SeriesDao
    abstract fun episodes(): EpisodeDao
    abstract fun library(): LibraryDao

    companion object {
        fun create(context: Context): NimbusDb =
            Room.databaseBuilder(context, NimbusDb::class.java, "nimbus.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                // Fire TV sticks report as low-RAM devices, where Room would otherwise pick TRUNCATE.
                // WAL lets the guide keep reading while a big EPG import is being written.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .fallbackToDestructiveMigration(true)
                .build()
    }
}
