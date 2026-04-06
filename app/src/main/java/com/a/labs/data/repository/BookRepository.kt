package com.a.labs.data.repository

import com.a.labs.data.local.room.dao.BookDao
import com.a.labs.data.local.room.entity.BookEntity
import com.a.labs.data.local.room.entity.ChunkEntity
import com.a.labs.data.local.room.entity.PageEntity
import kotlinx.coroutines.flow.Flow

class BookRepository(private val bookDao: BookDao) {

    suspend fun insertBook(book: BookEntity) {
        bookDao.insertBook(book)
    }

    fun getAllBooks(): Flow<List<BookEntity>> {
        return bookDao.getAllBooks()
    }

    suspend fun getBookById(bookId: String): BookEntity? {
        return bookDao.getBookById(bookId)
    }

    suspend fun updateLastReadPage(bookId: String, pageNumber: Int) {
        bookDao.updateLastReadPage(bookId, pageNumber)
    }

    suspend fun deleteBook(bookId: String) {
        bookDao.deleteBook(bookId)
    }

    suspend fun insertChunk(chunk: ChunkEntity) {
        bookDao.insertChunk(chunk)
    }

    suspend fun getChunksForBook(bookId: String): List<ChunkEntity> {
        return bookDao.getChunksForBook(bookId)
    }

    suspend fun getChunksByStatus(status: String): List<ChunkEntity> {
        return bookDao.getChunksByStatus(status)
    }

    suspend fun updateChunkStatus(chunkId: String, status: String, uri: String?) {
        bookDao.updateChunkStatus(chunkId, status, uri)
    }

    suspend fun insertPages(pages: List<PageEntity>) {
        bookDao.insertPages(pages)
    }

    fun getPagesForBook(bookId: String): Flow<List<PageEntity>> {
        return bookDao.getPagesForBook(bookId)
    }

    suspend fun getPageByNumber(bookId: String, pageNumber: Int): PageEntity? {
        return bookDao.getPageByNumber(bookId, pageNumber)
    }
}