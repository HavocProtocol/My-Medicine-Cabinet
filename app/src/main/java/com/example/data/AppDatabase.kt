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
                .addCallback(DatabaseCallback(context.applicationContext))
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }

    private class DatabaseCallback(private val context: Context) : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            INSTANCE?.let { database ->
                CoroutineScope(Dispatchers.IO).launch {
                    val dao = database.appDao()
                    val defaults = listOf(
                        context.getString(com.example.R.string.cat_respiratory),
                        context.getString(com.example.R.string.cat_gastro),
                        context.getString(com.example.R.string.cat_ent),
                        context.getString(com.example.R.string.cat_painkillers),
                        context.getString(com.example.R.string.cat_allergy),
                        context.getString(com.example.R.string.cat_skin),
                        context.getString(com.example.R.string.cat_eye),
                        context.getString(com.example.R.string.cat_vitamins),
                        context.getString(com.example.R.string.cat_first_aid),
                        context.getString(com.example.R.string.cat_chronic),
                        context.getString(com.example.R.string.cat_custom)
                    )
                    defaults.forEach { name ->
                        dao.insertCategory(Category(name = name, isDefault = true, description = null, color = null, icon = null))
                    }
                }
            }
        }
    }
}
