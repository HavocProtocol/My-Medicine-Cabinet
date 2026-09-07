package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(entities = [
    Medicine::class,
    InventoryItem::class,
    Category::class,
    MedicineCategoryCrossRef::class,
    FamilyMember::class,
    Alert::class,
    SourceReference::class
], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "dawaey_database"
                )
                .addCallback(DatabaseCallback())
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }

    private class DatabaseCallback : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            INSTANCE?.let { database ->
                CoroutineScope(Dispatchers.IO).launch {
                    val dao = database.appDao()
                    val defaults = listOf(
                        "صدرية وتنفسية",
                        "بطنية وهضمية",
                        "أنف وأذن وحنجرة",
                        "مسكنات وخافضات حرارة",
                        "حساسية",
                        "جلدية",
                        "عين",
                        "فيتامينات ومكملات",
                        "إسعافات أولية",
                        "أدوية مزمنة",
                        "تصنيف مخصص"
                    )
                    defaults.forEach { name ->
                        dao.insertCategory(Category(name = name, isDefault = true, description = null, color = null, icon = null))
                    }
                }
            }
        }
    }
}
