package com.plugin.notes.editor

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.plugin.notes.services.TodoNotesService
import com.plugin.notes.ui.NotesIcons
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingConstants

class TodoNoteLineMarkerProvider : LineMarkerProvider {

    private val mdParser = Parser.builder()
        .extensions(listOf(TablesExtension.create(), TaskListItemsExtension.create()))
        .build()

    private val mdRenderer = HtmlRenderer.builder()
        .extensions(listOf(TablesExtension.create(), TaskListItemsExtension.create()))
        .build()

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // Only inspect leaf comments or elements containing TODO/FIXME
        val isComment = element is PsiComment || element.node?.elementType?.toString()?.contains("COMMENT", ignoreCase = true) == true
        if (!isComment && element.firstChild != null) {
            return null
        }

        val rawText = element.text ?: return null
        if (!rawText.contains("TODO", ignoreCase = true) && !rawText.contains("FIXME", ignoreCase = true)) {
            return null
        }

        val project = element.project
        val psiFile = element.containingFile ?: return null
        val virtualFile = psiFile.virtualFile ?: return null
        val basePath = project.basePath ?: return null

        val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        val line = doc.getLineNumber(element.textRange.startOffset)
        val relPath = virtualFile.path.removePrefix(basePath).removePrefix("/")

        val todoService = TodoNotesService.getInstance(project)
        val cleanedText = todoService.extractTodoText(rawText)
        val hasNote = todoService.hasNote(relPath, line, cleanedText)
        val noteContent = if (hasNote) todoService.getNote(relPath, line, cleanedText) else ""

        val icon = if (hasNote) NotesIcons.GutterNote else NotesIcons.GutterNoteAdd

        val tooltipProvider = { _: PsiElement ->
            if (hasNote) {
                val document = mdParser.parse(noteContent)
                val renderedHtml = mdRenderer.render(document)
                val isBright = JBColor.isBright()
                val codeBg = if (isBright) "#EEEEEE" else "#2D3035"
                val codeFg = if (isBright) "#CF222E" else "#F78C6C"

                """
                <html>
                <head>
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; margin: 4px; font-size: 11pt; }
                        .header { font-weight: bold; color: #589DF6; border-bottom: 1px solid #589DF6; padding-bottom: 3px; margin-bottom: 6px; font-size: 11pt; }
                        .content { margin: 4px 0; }
                        .footer { margin-top: 8px; font-size: 9pt; color: #888888; font-style: italic; }
                        code { background-color: $codeBg; color: $codeFg; font-family: monospace; padding: 1px 3px; }
                        pre { background-color: $codeBg; font-family: monospace; padding: 4px; }
                    </style>
                </head>
                <body>
                    <div class="header">Note TODO &bull; ${virtualFile.name}:${line + 1}</div>
                    <div class="content">$renderedHtml</div>
                    <div class="footer">Clic pour modifier cette note</div>
                </body>
                </html>
                """.trimIndent()
            } else {
                """
                <html>
                <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; margin: 4px; font-size: 11pt;">
                    <div style="font-weight: bold; color: #589DF6; margin-bottom: 2px;">
                        TODO : ${escapeHtml(cleanedText.take(50))}
                    </div>
                    <div style="font-size: 9pt; color: #888888; font-style: italic;">
                        Clic pour ajouter une note
                    </div>
                </body>
                </html>
                """.trimIndent()
            }
        }

        val navHandler = GutterIconNavigationHandler<PsiElement> { mouseEvent, _ ->
            showNotePopup(project, psiFile, virtualFile, relPath, line, cleanedText, noteContent, hasNote, todoService, mouseEvent)
        }

        return LineMarkerInfo(
            element,
            element.textRange,
            icon,
            tooltipProvider,
            navHandler,
            GutterIconRenderer.Alignment.LEFT,
            { "Note TODO" }
        )
    }

    private fun showNotePopup(
        project: Project,
        psiFile: PsiFile,
        virtualFile: VirtualFile,
        relPath: String,
        line: Int,
        cleanedText: String,
        currentNote: String,
        hasNote: Boolean,
        service: TodoNotesService,
        mouseEvent: MouseEvent?
    ) {
        val popupPanel = JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(8)
            preferredSize = Dimension(400, 240)
        }

        val titleBar = JPanel(BorderLayout()).apply {
            val titleLabel = JLabel("Note TODO : ${virtualFile.name}:${line + 1}", NotesIcons.GutterNote, SwingConstants.LEFT).apply {
                font = font.deriveFont(Font.BOLD, 12f)
            }
            add(titleLabel, BorderLayout.WEST)
        }
        popupPanel.add(titleBar, BorderLayout.NORTH)

        val textArea = JBTextArea(currentNote).apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            lineWrap = true
            wrapStyleWord = true
            margin = JBUI.insets(6)
        }
        popupPanel.add(JBScrollPane(textArea), BorderLayout.CENTER)

        val bottomBar = JPanel(BorderLayout()).apply {
            val tipLabel = JLabel("Ctrl+Entrée pour enregistrer").apply {
                font = font.deriveFont(10f)
                foreground = JBColor.GRAY
            }
            add(tipLabel, BorderLayout.WEST)

            val buttonsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
            val btnSave = JButton("Enregistrer", AllIcons.Actions.Commit)
            val btnDelete = JButton("Effacer", AllIcons.General.Remove).apply {
                isEnabled = hasNote
            }
            buttonsPanel.add(btnDelete)
            buttonsPanel.add(btnSave)
            add(buttonsPanel, BorderLayout.EAST)
        }
        popupPanel.add(bottomBar, BorderLayout.SOUTH)

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(popupPanel, textArea)
            .setTitle("Note attachée au TODO")
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .createPopup()

        fun performSave() {
            val content = textArea.text.trim()
            service.saveNote(relPath, line, cleanedText, content)
            DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
            popup.cancel()
        }

        fun performDelete() {
            service.saveNote(relPath, line, cleanedText, "")
            DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
            popup.cancel()
        }

        val btnSave = (bottomBar.getComponent(1) as JPanel).getComponent(1) as JButton
        val btnDelete = (bottomBar.getComponent(1) as JPanel).getComponent(0) as JButton
        btnSave.addActionListener { performSave() }
        btnDelete.addActionListener { performDelete() }

        val mask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        textArea.registerKeyboardAction(
            { performSave() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, mask),
            JComponent.WHEN_FOCUSED
        )

        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (mouseEvent != null) {
            popup.show(RelativePoint(mouseEvent))
        } else if (editor != null) {
            popup.showInBestPositionFor(editor)
        } else {
            popup.showInFocusCenter()
        }
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
