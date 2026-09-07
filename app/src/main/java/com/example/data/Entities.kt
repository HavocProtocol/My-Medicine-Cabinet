package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "medicines",
    foreignKeys = [ForeignKey(entity = SourceReference::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.SET_NULL)]
)
data class Medicine(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val tradeName: String,
    val scientificName: String?,
    val barcode: String?,
    val form: String,
    val strength: String?,
    val manufacturer: String?,
    val imagePath: String?,
    val indications: String?,
    val warnings: String?,
    @ColumnInfo(index = true) val sourceId: Int?,
    val createdAt: Long,
    val isSynced: Boolean = false
)

@Entity(tableName = "inventory_items",
    foreignKeys = [
        ForeignKey(entity = Medicine::class, parentColumns = ["id"], childColumns = ["medicineId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FamilyMember::class, parentColumns = ["id"], childColumns = ["familyMemberId"], onDelete = ForeignKey.SET_NULL)
    ]
)
data class InventoryItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(index = true) val medicineId: Int,
    @ColumnInfo(index = true) val familyMemberId: Int?,
    val quantity: Double,
    val unit: String?,
    val purchaseDate: Long?,
    val expiryDate: Long,
    val batchNumber: String?,
    val storageLocation: String?,
    val needsRefrigeration: Boolean = false,
    val usageMode: String?,
    val userNotes: String?,
    val isSynced: Boolean = false
)

@Entity(tableName = "categories", indices = [Index(value = ["name"], unique = true)])
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val description: String?,
    val color: String?,
    val icon: String?,
    val isDefault: Boolean = false
)

@Entity(tableName = "medicine_categories",
    primaryKeys = ["medicineId", "categoryId"],
    foreignKeys = [
        ForeignKey(entity = Medicine::class, parentColumns = ["id"], childColumns = ["medicineId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Category::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.CASCADE)
    ]
)
data class MedicineCategoryCrossRef(
    val medicineId: Int,
    @ColumnInfo(index = true) val categoryId: Int
)

@Entity(tableName = "family_members")
data class FamilyMember(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val birthDate: Long?,
    val healthNotes: String?,
    val isChild: Boolean = false
)

@Entity(tableName = "alerts",
    foreignKeys = [ForeignKey(entity = InventoryItem::class, parentColumns = ["id"], childColumns = ["inventoryItemId"], onDelete = ForeignKey.CASCADE)]
)
data class Alert(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(index = true) val inventoryItemId: Int,
    val type: String,
    val triggerDate: Long,
    val isRead: Boolean = false,
    val createdAt: Long
)

@Entity(tableName = "source_references")
data class SourceReference(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val medicineId: Int?,
    val sourceName: String,
    val url: String?,
    val accessedAt: Long?,
    val lastUpdatedAt: Long?
)

data class AlertWithMedicine(
    @Embedded val alert: Alert,
    val tradeName: String,
    val expiryDate: Long
)
