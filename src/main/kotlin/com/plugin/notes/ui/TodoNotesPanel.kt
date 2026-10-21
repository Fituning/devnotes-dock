package com.plugin.notes.ui

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.plugin.notes.model.TodoItemModel
import com.plugin.notes.services.TodoNotesService
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.Timer
import java.util.TimerTask
import javax.swing.*

class TodoNotesPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val todoService = TodoNotesService.getInstance(project)
    private val allTodos = mutableListOf<TodoItemModel>()
    private val listModel = DefaultListModel<TodoItemModel>()
    private val todoList = JBList(listModel)

    private var selectedTodo: TodoItemModel? = null
    private var saveTimer: Timer? = null

    // Filter controls
    private val searchField = JTextField()
    private val onlyWithNotesCheckbox = JCheckBox("Avec note uniquement")

    // Right editor components
    private val headerLabel = JLabel("Sélectionnez un TODO dans la liste")
    private val btnGoToCode = JButton("Aller au code", AllIcons.Actions.Find).apply {
        isEnabled = false
    }
    private val btnDeleteNote = JButton("Effacer la note", AllIcons.General.Remove).apply {
        isEnabled = false
    }
    private val noteTextArea = JTextArea().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        margin = JBUI.insets(10)
        lineWrap = true
        wrapStyleWord = true
        isEnabled = false
    }

    init {
        setupUI()
        refreshTodos()
    }

    private fun setupUI() {
        val splitter = JBSplitter(false, 0.45f)

        // --- Left Panel: Filter + TODOs List ---
        val leftPanel = JPanel(BorderLayout())

        val filterPanel = JPanel(BorderLayout(4, 4)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                JBUI.Borders.empty(4)
            )
            val searchBox = JPanel(BorderLayout(4, 0))
            searchBox.add(JLabel(AllIcons.Actions.Search), BorderLayout.WEST)
            searchBox.add(searchField, BorderLayout.CENTER)

            val btnRefresh = JButton(AllIcons.Actions.Refresh).apply {
                toolTipText = "Re-scanner les TODOs du projet"
                preferredSize = Dimension(28, 24)
                addActionListener { refreshTodos() }
            }
            searchBox.add(btnRefresh, BorderLayout.EAST)

            add(searchBox, BorderLayout.NORTH)
            add(onlyWithNotesCheckbox, BorderLayout.SOUTH)
        }
        leftPanel.add(filterPanel, BorderLayout.NORTH)

        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent?) {
                applyFilter()
            }
        })
        onlyWithNotesCheckbox.addActionListener { applyFilter() }

        todoList.cellRenderer = TodoListCellRenderer()
        todoList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        todoList.addListSelectionListener {
            val todo = todoList.selectedValue
            if (todo != null) {
                selectTodo(todo)
            }
        }
        todoList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    navigateToSelectedCode()
                }
            }
        })

        leftPanel.add(JBScrollPane(todoList), BorderLayout.CENTER)
        splitter.firstComponent = leftPanel

        // --- Right Panel: Header + Note Editor ---
        val rightPanel = JPanel(BorderLayout())

        val topHeader = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                JBUI.Borders.empty(4, 8)
            )
            headerLabel.font = headerLabel.font.deriveFont(Font.BOLD, 12f)
            add(headerLabel, BorderLayout.CENTER)

            val actionButtons = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
            btnGoToCode.addActionListener { navigateToSelectedCode() }
            btnDeleteNote.addActionListener { deleteCurrentNote() }
            actionButtons.add(btnGoToCode)
            actionButtons.add(btnDeleteNote)
            add(actionButtons, BorderLayout.EAST)
        }
        rightPanel.add(topHeader, BorderLayout.NORTH)

        noteTextArea.addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent?) {
                scheduleAutoSave()
            }
        })

        rightPanel.add(JBScrollPane(noteTextArea), BorderLayout.CENTER)

        // Formatting toolbar at bottom
        val formatToolbar = createFormattingToolbar()
        rightPanel.add(formatToolbar, BorderLayout.SOUTH)

        splitter.secondComponent = rightPanel
        add(splitter, BorderLayout.CENTER)
    }

    private fun createFormattingToolbar(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            border = BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border())
        }

        fun addAction(title: String, prefix: String, suffix: String = "") {
            val btn = JButton(title).apply {
                font = font.deriveFont(10f)
                preferredSize = Dimension(48, 22)
                addActionListener {
                    if (noteTextArea.isEnabled) {
                        val start = noteTextArea.selectionStart
                        val end = noteTextArea.selectionEnd
                        val sel = noteTextArea.selectedText ?: ""
                        noteTextArea.replaceRange("$prefix$sel$suffix", start, end)
                        noteTextArea.requestFocusInWindow()
                        scheduleAutoSave()
                    }
                }
            }
            panel.add(btn)
        }

        addAction("B", "**", "**")
        addAction("I", "*", "*")
        addAction("[ ]", "- [ ] ")
        addAction("[x]", "- [x] ")
        addAction("Code", "```\n", "\n```")

        val lblTip = JLabel(" (Note liée à ce TODO)").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(10f)
        }
        panel.add(lblTip)

        return panel
    }

    fun refreshTodos() {
        SwingUtilities.invokeLater {
            allTodos.clear()
            allTodos.addAll(todoService.scanTodos())
            applyFilter()
        }
    }

    private fun applyFilter() {
        val query = searchField.text.trim().lowercase()
        val onlyNotes = onlyWithNotesCheckbox.isSelected

        listModel.clear()
        val filtered = allTodos.filter { item ->
            val matchesQuery = query.isEmpty() ||
                    item.fileName.lowercase().contains(query) ||
                    item.filePath.lowercase().contains(query) ||
                    item.todoText.lowercase().contains(query) ||
                    item.noteContent.lowercase().contains(query)

            val matchesNote = !onlyNotes || item.hasNote
            matchesQuery && matchesNote
        }

        for (item in filtered) {
            listModel.addElement(item)
        }

        if (selectedTodo != null) {
            val idx = filtered.indexOfFirst { it.id == selectedTodo?.id }
            if (idx != -1) {
                todoList.selectedIndex = idx
            }
        }
    }

    private fun selectTodo(todo: TodoItemModel) {
        saveCurrentNote()
        selectedTodo = todo
        headerLabel.text = "${todo.fileName}:${todo.displayLine} - ${todo.todoText.take(40)}"
        btnGoToCode.isEnabled = true
        btnDeleteNote.isEnabled = todo.hasNote
        noteTextArea.isEnabled = true
        noteTextArea.text = todo.noteContent
    }

    private fun navigateToSelectedCode() {
        val todo = selectedTodo ?: return
        val basePath = project.basePath ?: return
        val fullPath = File(basePath, todo.filePath).absolutePath
        val vFile = LocalFileSystem.getInstance().findFileByPath(fullPath) ?: return

        val descriptor = OpenFileDescriptor(project, vFile, todo.line, 0)
        descriptor.navigate(true)
    }

    private fun deleteCurrentNote() {
        val todo = selectedTodo ?: return
        todoService.saveNote(todo.filePath, todo.line, todo.todoText, "")
        todo.noteContent = ""
        noteTextArea.text = ""
        btnDeleteNote.isEnabled = false
        triggerGutterRefresh(todo.filePath)
        todoList.repaint()
    }

    private fun scheduleAutoSave() {
        saveTimer?.cancel()
        saveTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    SwingUtilities.invokeLater { saveCurrentNote() }
                }
            }, 600)
        }
    }

    private fun saveCurrentNote() {
        val todo = selectedTodo ?: return
        val content = noteTextArea.text.trim()
        todoService.saveNote(todo.filePath, todo.line, todo.todoText, content)
        todo.noteContent = content
        btnDeleteNote.isEnabled = todo.hasNote
        triggerGutterRefresh(todo.filePath)
        todoList.repaint()
    }

    private fun triggerGutterRefresh(relPath: String) {
        val basePath = project.basePath ?: return
        val fullPath = File(basePath, relPath).absolutePath
        val vFile = LocalFileSystem.getInstance().findFileByPath(fullPath) ?: return
        val psiFile = PsiManager.getInstance(project).findFile(vFile) ?: return
        DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
    }

    private class TodoListCellRenderer : ListCellRenderer<TodoItemModel> {
        private val panel = JPanel(BorderLayout(6, 2)).apply {
            border = JBUI.Borders.empty(4, 6)
        }
        private val titleLabel = JLabel()
        private val subtitleLabel = JLabel().apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(11f)
        }
        private val statusLabel = JLabel()

        init {
            val textPanel = JPanel(GridLayout(2, 1))
            textPanel.isOpaque = false
            textPanel.add(titleLabel)
            textPanel.add(subtitleLabel)
            panel.add(textPanel, BorderLayout.CENTER)
            panel.add(statusLabel, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out TodoItemModel>?,
            value: TodoItemModel?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            if (value != null) {
                titleLabel.text = value.todoText.take(65)
                subtitleLabel.text = "${value.fileName}:${value.displayLine}"

                if (value.hasNote) {
                    statusLabel.text = "Note"
                    statusLabel.icon = NotesIcons.NoteBadge
                    statusLabel.foreground = JBColor(0x2E7D32, 0x81C784)
                    panel.toolTipText = "<html><b>Note attachée :</b><br/>${escapeHtml(value.noteContent.take(150))}${if (value.noteContent.length > 150) "..." else ""}</html>"
                } else {
                    statusLabel.text = ""
                    statusLabel.icon = null
                    panel.toolTipText = "Double-clic pour aller au code"
                }
            }

            panel.background = if (isSelected) JBColor.namedColor("List.selectionBackground", Color(0x3B4354)) else list?.background
            titleLabel.foreground = if (isSelected) JBColor.namedColor("List.selectionForeground", Color.WHITE) else list?.foreground

            return panel
        }

        private fun escapeHtml(text: String): String {
            return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
        }
    }
}
