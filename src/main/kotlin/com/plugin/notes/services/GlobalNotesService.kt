package com.plugin.notes.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import java.io.File
import java.nio.charset.StandardCharsets

@Service(Service.Level.APP)
class GlobalNotesService {

    private val rootDir: File by lazy {
        val home = System.getProperty("user.home")
        val dir = File(home, ".jetbrains-notes")
        if (!dir.exists()) {
            dir.mkdirs()
            seedInitialNotes(dir)
        }
        dir
    }

    private fun seedInitialNotes(dir: File) {
        val welcome = File(dir, "Bienvenue.md")
        if (!welcome.exists()) {
            welcome.writeText(
                """
                # Notes Globales Partagées
                Ces notes sont accessibles depuis tous vos projets (WebStorm, IntelliJ, etc.).
                
                ### Fonctionnalités :
                - [x] Support Markdown standard
                - [ ] Listes de tâches interactives à cocher
                - [ ] Organisation en dossiers / arborescence
                
                ### Idées d'utilisation :
                - Mémos de commandes terminal fréquentes
                - Snippets et syntaxes utiles
                - Idées et todo-lists globales
                """.trimIndent(),
                StandardCharsets.UTF_8
            )
        }
    }

    fun getRootDirectory(): File = rootDir

    fun createNote(parentDir: File, fileName: String, initialContent: String = ""): File {
        val nameWithExt = if (fileName.contains('.')) fileName else "$fileName.md"
        val file = File(parentDir, nameWithExt)
        if (!file.exists()) {
            file.writeText(initialContent, StandardCharsets.UTF_8)
        }
        return file
    }

    fun createFolder(parentDir: File, folderName: String): File {
        val folder = File(parentDir, folderName)
        if (!folder.exists()) {
            folder.mkdirs()
        }
        return folder
    }

    fun readNote(file: File): String {
        return if (file.exists() && file.isFile) {
            file.readText(StandardCharsets.UTF_8)
        } else {
            ""
        }
    }

    fun saveNote(file: File, content: String) {
        if (file.parentFile != null && !file.parentFile.exists()) {
            file.parentFile.mkdirs()
        }
        file.writeText(content, StandardCharsets.UTF_8)
    }

    fun deleteItem(file: File): Boolean {
        return if (file.isDirectory) {
            file.deleteRecursively()
        } else {
            file.delete()
        }
    }

    fun renameItem(file: File, newName: String): File? {
        val target = File(file.parentFile, newName)
        return if (file.renameTo(target)) target else null
    }

    companion object {
        fun getInstance(): GlobalNotesService = service()
    }
}
