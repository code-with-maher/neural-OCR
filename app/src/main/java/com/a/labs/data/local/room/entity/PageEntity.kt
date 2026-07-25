package com.a.labs.data.local.room.entity

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

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
