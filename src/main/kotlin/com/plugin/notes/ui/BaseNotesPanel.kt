package com.plugin.notes.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.plugin.notes.model.NoteFileItem
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Timer
import java.util.TimerTask
import javax.swing.*
import javax.swing.event.HyperlinkEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

open class BaseNotesPanel(
    private val rootDirProvider: () -> File,
    private val isProjectScope: Boolean,
    private val onTransferRequest: ((File) -> Unit)? = null
) : JPanel(BorderLayout()) {

    private val rootNode = DefaultMutableTreeNode("Notes")
    private val treeModel = DefaultTreeModel(rootNode)
    private val fileTree = Tree(treeModel)
    private val searchField = JTextField()

    private var currentFile: File? = null
    private var saveTimer: Timer? = null

    // Editor components
    private val filePathLabel = JLabel("Sélectionnez ou créez une note").apply {
        font = font.deriveFont(Font.BOLD, 12f)
        border = JBUI.Borders.empty(4, 8)
    }

    private val editorTextArea = JTextArea().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        margin = JBUI.insets(10)
        lineWrap = true
        wrapStyleWord = true
    }

    private val previewPane = JEditorPane().apply {
        isEditable = false
        contentType = "text/html"
        margin = JBUI.insets(10)
    }

    private val cardLayout = CardLayout()
    private val contentCards = JPanel(cardLayout)
    private var isPreviewMode = false

    private val mdParser = Parser.builder()
        .extensions(listOf(TablesExtension.create(), TaskListItemsExtension.create()))
        .build()

    private val mdRenderer = HtmlRenderer.builder()
        .extensions(listOf(TablesExtension.create(), TaskListItemsExtension.create()))
        .build()

    init {
        setupUI()
        refreshTree()
    }

    private fun setupUI() {
        val splitter = JBSplitter(false, 0.30f)

        // --- Left Panel: Filter + Tree + Toolbar ---
        val leftPanel = JPanel(BorderLayout())

        val topHeaderPanel = JPanel(BorderLayout()).apply {
            val treeToolbar = createTreeToolbar()
            val searchBox = JPanel(BorderLayout(4, 0)).apply {
                border = JBUI.Borders.empty(2, 4, 4, 4)
                add(JLabel(AllIcons.Actions.Search), BorderLayout.WEST)
                add(searchField, BorderLayout.CENTER)
            }
            add(treeToolbar, BorderLayout.NORTH)
            add(searchBox, BorderLayout.SOUTH)
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())
        }
        leftPanel.add(topHeaderPanel, BorderLayout.NORTH)

        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent?) {
                refreshTree()
            }
        })

        fileTree.isRootVisible = false
        fileTree.showsRootHandles = true
        fileTree.cellRenderer = NoteTreeCellRenderer()

        // Enable Drag and Drop
        fileTree.dragEnabled = true
        fileTree.dropMode = DropMode.ON_OR_INSERT
        fileTree.transferHandler = TreeTransferHandler()

        // Right-click context menu
        fileTree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    val selRow = fileTree.getRowForLocation(e.x, e.y)
                    val selPath = fileTree.getPathForLocation(e.x, e.y)
                    if (selRow != -1 && selPath != null) {
                        fileTree.setSelectionPath(selPath)
                    }
                    showTreeContextMenu(e.x, e.y)
                }
            }
        })

        fileTree.addTreeSelectionListener {
            val selected = fileTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            val item = selected.userObject as? NoteFileItem
            if (item != null && !item.isDirectory) {
                loadFile(item.file)
            }
        }

        leftPanel.add(JBScrollPane(fileTree), BorderLayout.CENTER)
        splitter.firstComponent = leftPanel

        // --- Right Panel: Editor / Preview + Formatting Toolbar ---
        val rightPanel = JPanel(BorderLayout())

        val topBar = JPanel(BorderLayout()).apply {
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())
            add(filePathLabel, BorderLayout.WEST)
            add(createEditorActionsBar(), BorderLayout.EAST)
        }
        rightPanel.add(topBar, BorderLayout.NORTH)

        editorTextArea.addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent?) {
                scheduleAutoSave()
            }
        })

        previewPane.addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                val desc = e.description
                if (desc.startsWith("toggle-task:")) {
                    val lineIndex = desc.removePrefix("toggle-task:").toIntOrNull()
                    if (lineIndex != null) {
                        toggleTaskAtLine(lineIndex)
                    }
                }
            }
        }

        contentCards.add(JBScrollPane(editorTextArea), "EDIT")
        contentCards.add(JBScrollPane(previewPane), "PREVIEW")
        rightPanel.add(contentCards, BorderLayout.CENTER)

        val formatToolbar = createFormattingToolbar()
        rightPanel.add(formatToolbar, BorderLayout.SOUTH)

        splitter.secondComponent = rightPanel
        add(splitter, BorderLayout.CENTER)
    }

    private fun showTreeContextMenu(x: Int, y: Int) {
        val selected = fileTree.lastSelectedPathComponent as? DefaultMutableTreeNode
        val item = selected?.userObject as? NoteFileItem
        val menu = JPopupMenu()

        val itmMove = JMenuItem("Déplacer vers...", AllIcons.Actions.Forward).apply {
            addActionListener { moveSelectedDialog() }
        }
        val itmNewNote = JMenuItem("Nouvelle Note", AllIcons.General.Add).apply {
            addActionListener { createNewNoteDialog() }
        }
        val itmNewFolder = JMenuItem("Nouveau Dossier", AllIcons.Nodes.Folder).apply {
            addActionListener { createNewFolderDialog() }
        }
        val itmRename = JMenuItem("Renommer", AllIcons.Actions.Edit).apply {
            addActionListener { renameSelected() }
        }
        val itmDelete = JMenuItem("Supprimer", AllIcons.General.Remove).apply {
            addActionListener { deleteSelected() }
        }

        if (item != null) {
            menu.add(itmMove)
            menu.addSeparator()
        }
        menu.add(itmNewNote)
        menu.add(itmNewFolder)
        if (item != null) {
            menu.addSeparator()
            menu.add(itmRename)
            menu.add(itmDelete)
        }

        menu.show(fileTree, x, y)
    }

    private fun createTreeToolbar(): JComponent {
        val toolbarPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 2))

        val btnNewNote = JButton(AllIcons.General.Add).apply {
            toolTipText = "Nouvelle Note"
            preferredSize = Dimension(28, 24)
            addActionListener { createNewNoteDialog() }
        }

        val btnNewFolder = JButton(AllIcons.Nodes.Folder).apply {
            toolTipText = "Nouveau Dossier"
            preferredSize = Dimension(28, 24)
            addActionListener { createNewFolderDialog() }
        }

        val btnMove = JButton(AllIcons.Actions.Forward).apply {
            toolTipText = "Déplacer la sélection vers un dossier"
            preferredSize = Dimension(28, 24)
            addActionListener { moveSelectedDialog() }
        }

        val btnRename = JButton(AllIcons.Actions.Edit).apply {
            toolTipText = "Renommer"
            preferredSize = Dimension(28, 24)
            addActionListener { renameSelected() }
        }

        val btnDelete = JButton(AllIcons.General.Remove).apply {
            toolTipText = "Supprimer la sélection"
            preferredSize = Dimension(28, 24)
            addActionListener { deleteSelected() }
        }

        val btnRefresh = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText = "Rafraîchir l'arborescence"
            preferredSize = Dimension(28, 24)
            addActionListener { refreshTree() }
        }

        toolbarPanel.add(btnNewNote)
        toolbarPanel.add(btnNewFolder)
        toolbarPanel.add(btnMove)
        toolbarPanel.add(btnRename)
        toolbarPanel.add(btnDelete)
        toolbarPanel.add(btnRefresh)

        if (onTransferRequest != null) {
            val transferIcon = if (isProjectScope) AllIcons.Actions.Upload else AllIcons.Actions.Download
            val transferTooltip = if (isProjectScope) "Transférer la note vers les Notes Globales" else "Copier la note dans le Projet actuel"
            val btnTransfer = JButton(transferIcon).apply {
                toolTipText = transferTooltip
                preferredSize = Dimension(28, 24)
                addActionListener {
                    currentFile?.let { onTransferRequest.invoke(it) }
                }
            }
            toolbarPanel.add(btnTransfer)
        }

        return toolbarPanel
    }

    private fun createEditorActionsBar(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2))

        val btnOverlay = JButton(AllIcons.Actions.Preview).apply {
            toolTipText = "Aperçu de la note en overlay"
            preferredSize = Dimension(28, 24)
            addActionListener { showOverlayPreview() }
        }
        panel.add(btnOverlay)

        val btnToggle = JButton("Aperçu Markdown").apply {
            addActionListener {
                isPreviewMode = !isPreviewMode
                if (isPreviewMode) {
                    saveCurrentFile()
                    updatePreview()
                    cardLayout.show(contentCards, "PREVIEW")
                    text = "Mode Édition"
                } else {
                    cardLayout.show(contentCards, "EDIT")
                    text = "Aperçu Markdown"
                }
            }
        }
        panel.add(btnToggle)
        return panel
    }

    private fun showOverlayPreview() {
        val file = currentFile ?: return
        saveCurrentFile()

        val text = editorTextArea.text
        val html = buildFullHtml(text)

        val popupPane = JEditorPane().apply {
            isEditable = false
            contentType = "text/html"
            margin = JBUI.insets(12)
            this.text = html
        }

        val scrollPane = JBScrollPane(popupPane).apply {
            preferredSize = Dimension(520, 420)
            border = JBUI.Borders.empty()
        }

        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scrollPane, popupPane)
            .setTitle("Aperçu : ${file.name}")
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .createPopup()
            .showInFocusCenter()
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
                    insertMarkdownSnippet(prefix, suffix)
                }
            }
            panel.add(btn)
        }

        addAction("B", "**", "**")
        addAction("I", "*", "*")
        addAction("H1", "# ")
        addAction("H2", "## ")
        addAction("H3", "### ")
        addAction("[ ]", "- [ ] ")
        addAction("[x]", "- [x] ")
        addAction("Code", "```\n", "\n```")

        val lblTip = JLabel(" (Auto-sauvegarde active)").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(10f)
        }
        panel.add(lblTip)

        return panel
    }

    private fun insertMarkdownSnippet(prefix: String, suffix: String = "") {
        val start = editorTextArea.selectionStart
        val end = editorTextArea.selectionEnd
        val selectedText = editorTextArea.selectedText ?: ""

        val replacement = "$prefix$selectedText$suffix"
        editorTextArea.replaceRange(replacement, start, end)
        editorTextArea.requestFocusInWindow()
        scheduleAutoSave()
    }

    fun refreshTree() {
        val rootDir = rootDirProvider.invoke()
        rootNode.removeAllChildren()
        val query = searchField.text.trim().lowercase()
        populateTree(rootDir, rootNode, query)
        treeModel.reload()
    }

    private fun populateTree(dir: File, node: DefaultMutableTreeNode, query: String = "") {
        if (!dir.exists() || !dir.isDirectory) return

        val files = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return
        for (f in files) {
            if (f.name.startsWith(".")) continue

            val matches = query.isEmpty() || f.name.lowercase().contains(query)
            val item = NoteFileItem(
                file = f,
                name = f.name,
                isDirectory = f.isDirectory,
                relativePath = f.relativeTo(rootDirProvider.invoke()).path
            )
            val childNode = DefaultMutableTreeNode(item)

            if (f.isDirectory) {
                populateTree(f, childNode, query)
                if (query.isEmpty() || matches || childNode.childCount > 0) {
                    node.add(childNode)
                }
            } else {
                if (matches) {
                    node.add(childNode)
                }
            }
        }
    }

    private fun loadFile(file: File) {
        saveCurrentFile()
        currentFile = file
        filePathLabel.text = file.name
        val text = try {
            file.readText(StandardCharsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
        editorTextArea.text = text
        if (isPreviewMode) {
            updatePreview()
        }
    }

    private fun scheduleAutoSave() {
        saveTimer?.cancel()
        saveTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    SwingUtilities.invokeLater { saveCurrentFile() }
                }
            }, 800)
        }
    }

    fun saveCurrentFile() {
        val file = currentFile ?: return
        try {
            val text = editorTextArea.text
            file.writeText(text, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun moveFileToFolder(sourceFile: File, targetFolder: File): Boolean {
        if (sourceFile.parentFile.absolutePath == targetFolder.absolutePath) {
            return true
        }

        if (sourceFile.isDirectory && targetFolder.absolutePath.startsWith(sourceFile.absolutePath)) {
            Messages.showErrorDialog(this, "Impossible de déplacer un dossier à l'intérieur de lui-même.", "Déplacement impossible")
            return false
        }

        val dest = File(targetFolder, sourceFile.name)
        if (dest.exists()) {
            Messages.showErrorDialog(this, "Un élément nommé '${sourceFile.name}' existe déjà dans '${targetFolder.name}'.", "Conflit de nom")
            return false
        }

        val isCurrent = currentFile?.absolutePath == sourceFile.absolutePath
        val success = sourceFile.renameTo(dest)
        if (success) {
            refreshTree()
            if (isCurrent) {
                loadFile(dest)
            }
            return true
        } else {
            Messages.showErrorDialog(this, "Échec du déplacement de '${sourceFile.name}'.", "Erreur")
            return false
        }
    }

    private fun getAllFolders(root: File): List<File> {
        val result = mutableListOf<File>()
        result.add(root)
        if (root.exists() && root.isDirectory) {
            root.walkTopDown().filter { it.isDirectory && it != root && !it.name.startsWith(".") }.forEach {
                result.add(it)
            }
        }
        return result
    }

    private fun moveSelectedDialog() {
        val selected = fileTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val item = selected.userObject as? NoteFileItem ?: return
        val rootDir = rootDirProvider.invoke()

        val allFolders = getAllFolders(rootDir).filter { folder ->
            if (item.isDirectory) {
                !folder.absolutePath.startsWith(item.file.absolutePath)
            } else {
                true
            }
        }

        val displayOptions = allFolders.map { folder ->
            if (folder.absolutePath == rootDir.absolutePath) {
                "/ (Racine)"
            } else {
                "/" + folder.relativeTo(rootDir).path.replace("\\", "/")
            }
        }.toTypedArray()

        val currentParent = item.file.parentFile ?: rootDir
        val currentIdx = allFolders.indexOfFirst { it.absolutePath == currentParent.absolutePath }.coerceAtLeast(0)

        val selectedIdx = Messages.showChooseDialog(
            null as com.intellij.openapi.project.Project?,
            "Déplacer '${item.name}' vers :",
            "Déplacer",
            AllIcons.Actions.Forward,
            displayOptions,
            displayOptions[currentIdx]
        )

        if (selectedIdx >= 0 && selectedIdx < allFolders.size) {
            moveFileToFolder(item.file, allFolders[selectedIdx])
        }
    }

    private fun buildFullHtml(rawMarkdown: String): String {
        val lines = rawMarkdown.lines()
        val processedSb = StringBuilder()

        for ((idx, line) in lines.withIndex()) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("- [ ] ")) {
                val rest = trimmed.removePrefix("- [ ] ")
                processedSb.append("<p class=\"task-item\"><a href=\"toggle-task:$idx\" class=\"task-box task-box-todo\">[&nbsp;]</a> ").append(escapeHtml(rest)).append("</p>\n")
            } else if (trimmed.startsWith("- [x] ") || trimmed.startsWith("- [X] ")) {
                val rest = trimmed.removePrefix("- [x] ").removePrefix("- [X] ")
                processedSb.append("<p class=\"task-item\"><a href=\"toggle-task:$idx\" class=\"task-box task-box-done\">[&#10003;]</a> <span class=\"task-done-text\">").append(escapeHtml(rest)).append("</span></p>\n")
            } else {
                processedSb.append(line).append("\n")
            }
        }

        val document = mdParser.parse(processedSb.toString())
        val htmlBody = mdRenderer.render(document)

        val isBright = JBColor.isBright()
        val bgHex = if (isBright) "#FFFFFF" else "#1E1F22"
        val fgHex = if (isBright) "#1F2328" else "#BCBEC4"
        val borderHex = if (isBright) "#D0D7DE" else "#393B40"
        val headerHex = if (isBright) "#0969DA" else "#56A8F5"
        val subHeaderHex = if (isBright) "#1F6FEB" else "#73B0FF"
        val codeBgHex = if (isBright) "#F6F8FA" else "#26282E"
        val codeFgHex = if (isBright) "#CF222E" else "#F78C6C"
        val quoteBorderHex = if (isBright) "#0969DA" else "#3574F0"
        val quoteFgHex = if (isBright) "#57606A" else "#868A91"
        val tableThBgHex = if (isBright) "#F6F8FA" else "#2B2D30"
        val taskDoneFgHex = if (isBright) "#8C959F" else "#6F737A"

        return """
            <html>
            <head>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
                        background-color: $bgHex;
                        color: $fgHex;
                        margin: 12px 14px;
                        font-size: 11pt;
                    }
                    h1 {
                        color: $headerHex;
                        font-size: 15pt;
                        font-weight: 600;
                        margin-top: 10px;
                        margin-bottom: 8px;
                        padding-bottom: 4px;
                        border-bottom: 1px solid $borderHex;
                    }
                    h2 {
                        color: $subHeaderHex;
                        font-size: 13pt;
                        font-weight: 600;
                        margin-top: 10px;
                        margin-bottom: 6px;
                        padding-bottom: 3px;
                        border-bottom: 1px solid $borderHex;
                    }
                    h3 {
                        color: $subHeaderHex;
                        font-size: 11.5pt;
                        font-weight: 600;
                        margin-top: 8px;
                        margin-bottom: 4px;
                    }
                    p {
                        margin-top: 4px;
                        margin-bottom: 6px;
                    }
                    ul, ol {
                        margin-top: 4px;
                        margin-bottom: 6px;
                        padding-left: 18px;
                    }
                    li {
                        margin-bottom: 3px;
                    }
                    code {
                        background-color: $codeBgHex;
                        color: $codeFgHex;
                        font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
                        font-size: 10pt;
                        padding: 1px 4px;
                    }
                    pre {
                        background-color: $codeBgHex;
                        color: $fgHex;
                        font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
                        font-size: 10pt;
                        padding: 8px 10px;
                        border: 1px solid $borderHex;
                        margin: 8px 0;
                    }
                    blockquote {
                        margin: 8px 0;
                        padding: 4px 12px;
                        color: $quoteFgHex;
                        border-left: 3px solid $quoteBorderHex;
                        background-color: $codeBgHex;
                    }
                    table {
                        border-collapse: collapse;
                        margin: 8px 0;
                        width: 100%;
                    }
                    th {
                        background-color: $tableThBgHex;
                        color: $fgHex;
                        font-weight: 600;
                        padding: 5px 8px;
                        border: 1px solid $borderHex;
                        text-align: left;
                    }
                    td {
                        padding: 5px 8px;
                        border: 1px solid $borderHex;
                    }
                    a {
                        color: $headerHex;
                        text-decoration: none;
                    }
                    hr {
                        border: 0;
                        height: 1px;
                        background-color: $borderHex;
                        margin: 12px 0;
                    }
                    .task-item {
                        margin: 4px 0;
                    }
                    .task-box {
                        font-family: monospace;
                        font-weight: bold;
                        text-decoration: none;
                        padding: 0 4px;
                    }
                    .task-box-todo {
                        color: $headerHex;
                    }
                    .task-box-done {
                        color: #4CAF50;
                    }
                    .task-done-text {
                        color: $taskDoneFgHex;
                        text-decoration: line-through;
                    }
                </style>
            </head>
            <body>
                $htmlBody
            </body>
            </html>
        """.trimIndent()
    }

    private fun updatePreview() {
        val fullHtml = buildFullHtml(editorTextArea.text)
        try {
            previewPane.text = fullHtml
        } catch (e: Throwable) {
            previewPane.contentType = "text/plain"
            previewPane.text = editorTextArea.text
        }
    }

    private fun toggleTaskAtLine(lineIndex: Int) {
        val lines = editorTextArea.text.lines().toMutableList()
        if (lineIndex in lines.indices) {
            val line = lines[lineIndex]
            if (line.contains("- [ ] ")) {
                lines[lineIndex] = line.replaceFirst("- [ ] ", "- [x] ")
            } else if (line.contains("- [x] ")) {
                lines[lineIndex] = line.replaceFirst("- [x] ", "- [ ] ")
            } else if (line.contains("- [X] ")) {
                lines[lineIndex] = line.replaceFirst("- [X] ", "- [ ] ")
            }
            editorTextArea.text = lines.joinToString("\n")
            saveCurrentFile()
            updatePreview()
        }
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun getSelectedFolderOrRoot(): File {
        val selected = fileTree.lastSelectedPathComponent as? DefaultMutableTreeNode
        val item = selected?.userObject as? NoteFileItem
        return if (item != null) {
            if (item.isDirectory) item.file else item.file.parentFile
        } else {
            rootDirProvider.invoke()
        }
    }

    private fun createNewNoteDialog() {
        val folder = getSelectedFolderOrRoot()
        val name = Messages.showInputDialog(
            this,
            "Nom de la nouvelle note (ex: Tâches, Architecture) :",
            "Nouvelle Note",
            Messages.getQuestionIcon()
        )
        if (!name.isNullOrBlank()) {
            val nameWithExt = if (name.contains(".")) name else "$name.md"
            val newFile = File(folder, nameWithExt)
            if (!newFile.exists()) {
                newFile.writeText("# $name\n\n- [ ] Première tâche\n", StandardCharsets.UTF_8)
            }
            refreshTree()
            loadFile(newFile)
        }
    }

    private fun createNewFolderDialog() {
        val folder = getSelectedFolderOrRoot()
        val name = Messages.showInputDialog(
            this,
            "Nom du nouveau dossier (ex: specs, syntaxe) :",
            "Nouveau Dossier",
            Messages.getQuestionIcon()
        )
        if (!name.isNullOrBlank()) {
            val newFolder = File(folder, name)
            if (!newFolder.exists()) {
                newFolder.mkdirs()
            }
            refreshTree()
        }
    }

    private fun deleteSelected() {
        val selected = fileTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val item = selected.userObject as? NoteFileItem ?: return
        val confirm = Messages.showYesNoDialog(
            this,
            "Voulez-vous vraiment supprimer '${item.name}' ?",
            "Confirmation de suppression",
            Messages.getWarningIcon()
        )
        if (confirm == Messages.YES) {
            if (item.isDirectory) item.file.deleteRecursively() else item.file.delete()
            if (currentFile == item.file) {
                currentFile = null
                editorTextArea.text = ""
                filePathLabel.text = "Sélectionnez une note"
            }
            refreshTree()
        }
    }

    private fun renameSelected() {
        val selected = fileTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val item = selected.userObject as? NoteFileItem ?: return
        val newName = Messages.showInputDialog(
            this,
            "Nouveau nom :",
            "Renommer",
            Messages.getQuestionIcon(),
            item.name,
            null
        )
        if (!newName.isNullOrBlank() && newName != item.name) {
            val target = File(item.file.parentFile, newName)
            item.file.renameTo(target)
            if (currentFile == item.file) {
                loadFile(target)
            }
            refreshTree()
        }
    }

    private class NoteTransferable(val item: NoteFileItem) : Transferable {
        companion object {
            val FLAVOR = DataFlavor(NoteFileItem::class.java, "NoteFileItem")
        }

        override fun getTransferDataFlavors(): Array<DataFlavor> =
            arrayOf(FLAVOR, DataFlavor.javaFileListFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean =
            flavor == FLAVOR || flavor == DataFlavor.javaFileListFlavor

        override fun getTransferData(flavor: DataFlavor?): Any {
            if (flavor == FLAVOR) return item
            if (flavor == DataFlavor.javaFileListFlavor) return listOf(item.file)
            throw UnsupportedFlavorException(flavor)
        }
    }

    private inner class TreeTransferHandler : TransferHandler() {
        override fun getSourceActions(c: JComponent?): Int = MOVE

        override fun createTransferable(c: JComponent?): Transferable? {
            val selected = fileTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
            val item = selected.userObject as? NoteFileItem ?: return null
            return NoteTransferable(item)
        }

        override fun canImport(support: TransferSupport): Boolean {
            if (!support.isDrop) return false
            if (!support.isDataFlavorSupported(NoteTransferable.FLAVOR)) return false
            support.dropAction = MOVE

            val dropLocation = support.dropLocation as? JTree.DropLocation ?: return false
            val path = dropLocation.path ?: return true

            val targetNode = path.lastPathComponent as? DefaultMutableTreeNode ?: return false
            val targetItem = targetNode.userObject as? NoteFileItem

            val selected = fileTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return false
            val sourceItem = selected.userObject as? NoteFileItem ?: return false

            if (sourceItem.file.absolutePath == targetItem?.file?.absolutePath) return false
            if (sourceItem.isDirectory && targetItem != null) {
                if (targetItem.file.absolutePath.startsWith(sourceItem.file.absolutePath)) {
                    return false
                }
            }
            return true
        }

        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support)) return false

            val dropLocation = support.dropLocation as? JTree.DropLocation ?: return false
            val transferable = support.transferable
            val item = try {
                transferable.getTransferData(NoteTransferable.FLAVOR) as? NoteFileItem
            } catch (e: Exception) {
                null
            } ?: return false

            val rootDir = rootDirProvider.invoke()
            val path = dropLocation.path
            val targetFolder = if (path == null) {
                rootDir
            } else {
                val targetNode = path.lastPathComponent as? DefaultMutableTreeNode
                val targetItem = targetNode?.userObject as? NoteFileItem
                if (targetItem == null) {
                    rootDir
                } else if (targetItem.isDirectory) {
                    targetItem.file
                } else {
                    targetItem.file.parentFile ?: rootDir
                }
            }

            return moveFileToFolder(item.file, targetFolder)
        }
    }

    private class NoteTreeCellRenderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(
            tree: JTree?,
            value: Any?,
            sel: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ): java.awt.Component {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
            val node = value as? DefaultMutableTreeNode
            val item = node?.userObject as? NoteFileItem
            if (item != null) {
                text = item.name
                icon = if (item.isDirectory) {
                    if (expanded) AllIcons.Nodes.Folder else AllIcons.Nodes.Folder
                } else {
                    if (item.name.endsWith(".md", ignoreCase = true)) {
                        AllIcons.FileTypes.Text
                    } else {
                        AllIcons.FileTypes.Any_type
                    }
                }
            }
            return this
        }
    }
}
