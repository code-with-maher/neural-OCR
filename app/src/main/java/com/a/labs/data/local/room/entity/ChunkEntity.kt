package com.a.labs.data.local.room.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chunks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId")]
)
data class ChunkEntity(
    @PrimaryKey
    val id: String,
    val bookId: String,
    val startPage: Int,
    val endPage: Int,
    val filesApiUri: String? = null,
    val filesApiUriExpiration: Long? = null,
    val status: String 
)