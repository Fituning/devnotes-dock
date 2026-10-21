package com.plugin.notes.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.charset.StandardCharsets

@Service(Service.Level.PROJECT)
class ProjectNotesService(private val project: Project) {

    private val rootDir: File by lazy {
        val basePath = project.basePath ?: System.getProperty("user.home")
        val dir = File(basePath, ".idea/notes")
        if (!dir.exists()) {
            dir.mkdirs()
            seedInitialNotes(dir)
        }
        dir
    }

    private fun seedInitialNotes(dir: File) {
        val welcome = File(dir, "Notes-Projet.md")
        if (!welcome.exists()) {
            val projectName = project.name
            welcome.writeText(
                """
                # Notes pour le projet : $projectName
                Ces notes sont spécifiques à ce projet et ne sont pas mélangées avec les autres.
                
                ### Structure et Objectifs :
                - [ ] Configuration de l'environnement
                - [ ] Documentation des API
                - [ ] Déploiement
                
                ### Mémos :
                Vous pouvez créer des sous-dossiers (ex: `architecture/`, `specs/`, `bugs/`) pour organiser vos notes.
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

    fun transferToGlobal(sourceFile: File): File? {
        if (!sourceFile.exists() || sourceFile.isDirectory) return null
        val globalService = GlobalNotesService.getInstance()
        val content = readNote(sourceFile)
        return globalService.createNote(globalService.getRootDirectory(), sourceFile.name, content)
    }

    companion object {
        fun getInstance(project: Project): ProjectNotesService =
            project.getService(ProjectNotesService::class.java)
    }
}
