package com.a.labs.data.local.room.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pages",
    foreignKeys =[
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId")]
)
data class PageEntity(
    @PrimaryKey
    val id: String,
    val bookId: String,
    val pageNumber: Int,
    val markdownContent: String,
    val audioUri: String? = null
)