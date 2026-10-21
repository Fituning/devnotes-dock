package com.plugin.notes.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.plugin.notes.services.GlobalNotesService
import com.plugin.notes.services.ProjectNotesService
import java.io.File

class NotesToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()

        val projectService = ProjectNotesService.getInstance(project)
        val globalService = GlobalNotesService.getInstance()

        // Tab 1: Project Notes
        val projectNotesPanel = BaseNotesPanel(
            rootDirProvider = { projectService.getRootDirectory() },
            isProjectScope = true,
            onTransferRequest = { file ->
                val result = projectService.transferToGlobal(file)
                if (result != null) {
                    Messages.showInfoMessage(
                        project,
                        "La note '${file.name}' a été copiée vers vos Notes Globales !",
                        "Transfert Réussi"
                    )
                }
            }
        )
        val projectContent = contentFactory.createContent(projectNotesPanel, "Notes Projet", false)
        toolWindow.contentManager.addContent(projectContent)

        // Tab 2: Global Notes
        val globalNotesPanel = BaseNotesPanel(
            rootDirProvider = { globalService.getRootDirectory() },
            isProjectScope = false,
            onTransferRequest = { file ->
                projectService.createNote(
                    projectService.getRootDirectory(),
                    file.name,
                    globalService.readNote(file)
                )
                projectNotesPanel.refreshTree()
                Messages.showInfoMessage(
                    project,
                    "La note '${file.name}' a été copiée dans le projet actuel !",
                    "Copie Réussie"
                )
            }
        )
        val globalContent = contentFactory.createContent(globalNotesPanel, "Notes Globales", false)
        toolWindow.contentManager.addContent(globalContent)

        // Tab 3: Project TODOs & Notes
        val todoNotesPanel = TodoNotesPanel(project)
        val todoContent = contentFactory.createContent(todoNotesPanel, "TODOs & Notes", false)
        toolWindow.contentManager.addContent(todoContent)
    }
}
