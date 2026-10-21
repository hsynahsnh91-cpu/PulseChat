package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.db.AppDatabase
import com.example.data.model.ChatFolder
import com.example.data.model.ChatFolderMember
import com.example.data.model.FolderWithCount
import com.example.domain.Ids
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class FolderException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { EMPTY_NAME, NAME_TOO_LONG, DUPLICATE_NAME, LIMIT }
}

/** Custom chat folders, stored on this device for the signed-in account. */
class FolderRepository(private val db: AppDatabase, private val currentUid: () -> String?) {
    private val dao get() = db.folderDao()

    fun observeFolders(): Flow<List<ChatFolder>> = currentUid()?.let { dao.observeFolders(it) } ?: emptyFlow()
    fun observeFoldersWithCounts(): Flow<List<FolderWithCount>> = currentUid()?.let { dao.observeFoldersWithCounts(it) } ?: emptyFlow()
    fun observeMembers(): Flow<List<ChatFolderMember>> = currentUid()?.let { dao.observeMembers(it) } ?: emptyFlow()

    private suspend fun validName(name: String, exceptId: String?): String {
        val me = currentUid() ?: throw ChatException(ChatError.NOT_SIGNED_IN)
        val n = name.trim().replace(Regex("\\s+"), " ")
        if (n.isEmpty()) throw FolderException(FolderException.Reason.EMPTY_NAME)
        if (n.length > MAX_NAME) throw FolderException(FolderException.Reason.NAME_TOO_LONG)
        if (dao.folders(me).any { it.folderId != exceptId && it.name.equals(n, ignoreCase = true) }) {
            throw FolderException(FolderException.Reason.DUPLICATE_NAME)
        }
        return n
    }

    suspend fun create(name: String, conversationIds: Set<String>): ChatFolder {
        val me = currentUid() ?: throw ChatException(ChatError.NOT_SIGNED_IN)
        val n = validName(name, null)
        if (dao.folders(me).size >= MAX_FOLDERS) throw FolderException(FolderException.Reason.LIMIT)
        val now = System.currentTimeMillis()
        val folder = ChatFolder(Ids.newId("fld"), me, n, dao.maxOrder(me) + 1, now)
        db.withTransaction {
            dao.insertFolder(folder)
            if (conversationIds.isNotEmpty()) dao.addMembers(conversationIds.map { ChatFolderMember(folder.folderId, it, now) })
        }
        return folder
    }

    suspend fun update(folderId: String, name: String, conversationIds: Set<String>) {
        val n = validName(name, folderId)
        db.withTransaction {
            dao.rename(folderId, n)
            setMembers(folderId, conversationIds)
        }
    }

    /** Makes the folder contain exactly [conversationIds]. */
    suspend fun setMembers(folderId: String, conversationIds: Set<String>) = db.withTransaction {
        val now = System.currentTimeMillis()
        val current = dao.memberIds(folderId).toSet()
        val removed = current - conversationIds
        if (removed.isNotEmpty()) dao.removeMembers(folderId, removed.toList())
        val added = conversationIds - current
        if (added.isNotEmpty()) dao.addMembers(added.map { ChatFolderMember(folderId, it, now) })
    }

    suspend fun delete(folderId: String) = db.withTransaction {
        dao.deleteMembersOf(folderId)
        dao.deleteFolderRow(folderId)
    }

    suspend fun memberIds(folderId: String): Set<String> = dao.memberIds(folderId).toSet()

    suspend fun foldersOf(conversationId: String): Set<String> = dao.foldersOf(conversationId).toSet()

    /** Moves a folder one position up (-1) or down (+1) and renumbers all folders. */
    suspend fun move(folderId: String, delta: Int) {
        val me = currentUid() ?: return
        val list = dao.folders(me).toMutableList()
        val i = list.indexOfFirst { it.folderId == folderId }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        val moved = list.removeAt(i)
        list.add(j, moved)
        list.forEachIndexed { index, f -> if (f.sortOrder != index) dao.setOrder(f.folderId, index) }
    }

    /** Adds chats to the checked folders and removes them from unchecked ones. */
    suspend fun assign(conversationIds: Collection<String>, folderIds: Set<String>) {
        val me = currentUid() ?: return
        val now = System.currentTimeMillis()
        dao.folders(me).forEach { f ->
            if (f.folderId in folderIds) dao.addMembers(conversationIds.map { ChatFolderMember(f.folderId, it, now) })
            else dao.removeMembers(f.folderId, conversationIds.toList())
        }
    }

    companion object {
        const val MAX_NAME = 24
        const val MAX_FOLDERS = 20
    }
}
