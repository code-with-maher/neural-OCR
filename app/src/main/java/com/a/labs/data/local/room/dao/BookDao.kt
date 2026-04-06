package com.a.labs.data.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.a.labs.data.local.room.entity.BookEntity
import com.a.labs.data.local.room.entity.ChunkEntity
import com.a.labs.data.local.room.entity.PageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity)

    @Query("SELECT * FROM books ORDER BY createdAt DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBookById(bookId: String): BookEntity?

    @Query("UPDATE books SET lastReadPage = :pageNumber WHERE id = :bookId")
    suspend fun updateLastReadPage(bookId: String, pageNumber: Int)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBook(bookId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunk(chunk: ChunkEntity)

    @Query("SELECT * FROM chunks WHERE bookId = :bookId ORDER BY startPage ASC")
    suspend fun getChunksForBook(bookId: String): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE status = :status")
    suspend fun getChunksByStatus(status: String): List<ChunkEntity>

    @Query("UPDATE chunks SET status = :status, filesApiUri = :uri WHERE id = :chunkId")
    suspend fun updateChunkStatus(chunkId: String, status: String, uri: String?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPages(pages: List<PageEntity>)

    @Query("SELECT * FROM pages WHERE bookId = :bookId ORDER BY pageNumber ASC")
    fun getPagesForBook(bookId: String): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE bookId = :bookId AND pageNumber = :pageNumber")
    suspend fun getPageByNumber(bookId: String, pageNumber: Int): PageEntity?
}