package com.a.labs.data.local.room.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val sourcePdfUri: String,
    val totalPages: Int,
    val lastReadPage: Int = 1,
    val createdAt: Long = System.currentTimeMillis()
)