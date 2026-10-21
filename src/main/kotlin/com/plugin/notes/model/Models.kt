package com.plugin.notes.model

import java.io.File

data class NoteFileItem(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val relativePath: String
) {
    override fun toString(): String = name
}

data class TodoItemModel(
    val id: String,
    val filePath: String,
    val fileName: String,
    val line: Int,
    val todoText: String,
    var noteContent: String = ""
) {
    val displayLine: Int get() = line + 1
    val hasNote: Boolean get() = noteContent.isNotBlank()
}
