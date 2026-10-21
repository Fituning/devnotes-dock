package com.plugin.notes.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.PsiTodoSearchHelper
import com.plugin.notes.model.TodoItemModel
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class TodoNotesService(private val project: Project) {

    private val notesStoreFile: File by lazy {
        val basePath = project.basePath ?: System.getProperty("user.home")
        val dir = File(basePath, ".idea/notes")
        if (!dir.exists()) dir.mkdirs()
        File(dir, ".todo_notes.properties")
    }

    // Key -> Note content
    private val todoNotesMap = ConcurrentHashMap<String, String>()

    init {
        loadNotes()
    }

    private fun loadNotes() {
        if (!notesStoreFile.exists()) return
        try {
            val lines = notesStoreFile.readLines(StandardCharsets.UTF_8)
            var currentKey: String? = null
            val currentContent = StringBuilder()

            for (line in lines) {
                if (line.startsWith("KEY::")) {
                    if (currentKey != null) {
                        todoNotesMap[currentKey] = currentContent.toString().trim()
                        currentContent.clear()
                    }
                    currentKey = line.removePrefix("KEY::").trim()
                } else if (line == "---END_NOTE---") {
                    if (currentKey != null) {
                        todoNotesMap[currentKey] = currentContent.toString().trim()
                        currentContent.clear()
                        currentKey = null
                    }
                } else {
                    if (currentKey != null) {
                        currentContent.append(line).append("\n")
                    }
                }
            }
            if (currentKey != null) {
                todoNotesMap[currentKey] = currentContent.toString().trim()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun persistNotes() {
        try {
            val sb = StringBuilder()
            for ((key, content) in todoNotesMap) {
                if (content.isNotBlank()) {
                    sb.append("KEY::").append(key).append("\n")
                    sb.append(content).append("\n")
                    sb.append("---END_NOTE---\n")
                }
            }
            notesStoreFile.writeText(sb.toString(), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun extractTodoText(raw: String): String {
        val upper = raw.uppercase()
        val todoIdx = upper.indexOf("TODO")
        val fixmeIdx = upper.indexOf("FIXME")
        val startIdx = when {
            todoIdx != -1 && fixmeIdx != -1 -> minOf(todoIdx, fixmeIdx)
            todoIdx != -1 -> todoIdx
            fixmeIdx != -1 -> fixmeIdx
            else -> 0
        }
        val substring = raw.substring(startIdx).trim()
        val firstLine = substring.lines().firstOrNull()?.trim() ?: substring
        return firstLine
            .removeSuffix("*/")
            .removeSuffix("-->")
            .trim()
    }

    fun generateKey(filePath: String, line: Int, text: String): String {
        val normalizedPath = filePath.replace("\\", "/").trimStart('/')
        val cleanedText = extractTodoText(text).take(60)
        return "$normalizedPath:$line:${hash(cleanedText)}"
    }

    private fun hash(text: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(text.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(8)
    }

    fun getNote(filePath: String, line: Int, text: String): String {
        val normalizedPath = filePath.replace("\\", "/").trimStart('/')
        val cleanedText = extractTodoText(text).take(60)
        val exactKey = "$normalizedPath:$line:${hash(cleanedText)}"

        val exactMatch = todoNotesMap[exactKey]
        if (exactMatch != null && exactMatch.isNotBlank()) return exactMatch

        // 1st Fallback: match by filePath and line
        val prefixWithLine = "$normalizedPath:$line:"
        for ((k, v) in todoNotesMap) {
            if (k.startsWith(prefixWithLine) && v.isNotBlank()) {
                return v
            }
        }

        // 2nd Fallback: search by path and text hash if line shifted
        val textHash = hash(cleanedText)
        val prefix = "$normalizedPath:"
        for ((k, v) in todoNotesMap) {
            if (k.startsWith(prefix) && k.endsWith(":$textHash") && v.isNotBlank()) {
                return v
            }
        }
        return ""
    }

    fun hasNote(filePath: String, line: Int, text: String): Boolean {
        return getNote(filePath, line, text).isNotBlank()
    }

    fun saveNote(filePath: String, line: Int, text: String, note: String) {
        val normalizedPath = filePath.replace("\\", "/").trimStart('/')
        val cleanedText = extractTodoText(text).take(60)
        val key = "$normalizedPath:$line:${hash(cleanedText)}"

        if (note.isBlank()) {
            todoNotesMap.remove(key)
            val prefixWithLine = "$normalizedPath:$line:"
            val textHash = hash(cleanedText)
            val keysToRemove = todoNotesMap.keys.filter {
                it.startsWith(prefixWithLine) || (it.startsWith("$normalizedPath:") && it.endsWith(":$textHash"))
            }
            for (k in keysToRemove) {
                todoNotesMap.remove(k)
            }
        } else {
            todoNotesMap[key] = note.trim()
        }
        persistNotes()
    }

    fun scanTodos(): List<TodoItemModel> {
        val result = mutableListOf<TodoItemModel>()
        val basePath = project.basePath ?: return result

        ReadAction.run<Throwable> {
            try {
                val fileIndex = ProjectFileIndex.getInstance(project)
                val psiManager = PsiManager.getInstance(project)
                val todoSearchHelper = PsiTodoSearchHelper.getInstance(project)

                fileIndex.iterateContent { virtualFile ->
                    if (!virtualFile.isDirectory && !virtualFile.path.contains("/.idea/") && !virtualFile.path.contains("/.git/")) {
                        try {
                            val psiFile = psiManager.findFile(virtualFile)
                            if (psiFile != null) {
                                val todoItems = todoSearchHelper.findTodoItems(psiFile)
                                for (item in todoItems) {
                                    val doc = psiFile.viewProvider.document ?: continue
                                    val line = doc.getLineNumber(item.textRange.startOffset)
                                    val relPath = virtualFile.path.removePrefix(basePath).removePrefix("/")
                                    val rawText = item.textRange.substring(doc.text)
                                    val cleanedText = extractTodoText(rawText)
                                    val note = getNote(relPath, line, cleanedText)
                                    val id = generateKey(relPath, line, cleanedText)
                                    result.add(
                                        TodoItemModel(
                                            id = id,
                                            filePath = relPath,
                                            fileName = virtualFile.name,
                                            line = line,
                                            todoText = cleanedText,
                                            noteContent = note
                                        )
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            // Ignore files that fail PSI parsing
                        }
                    }
                    true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Fallback scan using regex if PSI search yielded nothing or for non-indexed files
        if (result.isEmpty()) {
            scanTodosViaRegex(basePath, result)
        }

        return result
    }

    private fun scanTodosViaRegex(basePath: String, result: MutableList<TodoItemModel> = mutableListOf()): List<TodoItemModel> {
        val projectDir = File(basePath)
        val todoRegex = Regex("""\b(TODO|FIXME)\b[:\s]*(.*)""", RegexOption.IGNORE_CASE)

        projectDir.walkTopDown()
            .filter { file ->
                file.isFile &&
                        !file.absolutePath.contains("/.git/") &&
                        !file.absolutePath.contains("/.idea/") &&
                        !file.absolutePath.contains("/build/") &&
                        !file.absolutePath.contains("/node_modules/") &&
                        !file.absolutePath.contains("/dist/") &&
                        file.length() < 1024 * 1024 // skip large files > 1MB
            }
            .forEach { file ->
                try {
                    val relPath = file.relativeTo(projectDir).path.replace("\\", "/").trimStart('/')
                    val lines = file.readLines(StandardCharsets.UTF_8)
                    for ((lineIdx, lineText) in lines.withIndex()) {
                        val match = todoRegex.find(lineText)
                        if (match != null) {
                            val matchedText = match.value
                            val cleanedText = extractTodoText(matchedText)
                            val note = getNote(relPath, lineIdx, cleanedText)
                            val id = generateKey(relPath, lineIdx, cleanedText)
                            result.add(
                                TodoItemModel(
                                    id = id,
                                    filePath = relPath,
                                    fileName = file.name,
                                    line = lineIdx,
                                    todoText = cleanedText,
                                    noteContent = note
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    // Ignore binary files or unreadable files
                }
            }
        return result
    }

    companion object {
        fun getInstance(project: Project): TodoNotesService =
            project.getService(TodoNotesService::class.java)
    }
}
