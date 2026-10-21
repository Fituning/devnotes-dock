package com.plugin.notes.ui

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object NotesIcons {
    @JvmField
    val ToolWindow: Icon = IconLoader.getIcon("/icons/notesToolWindow.svg", NotesIcons::class.java)

    @JvmField
    val GutterNote: Icon = IconLoader.getIcon("/icons/noteGutter.svg", NotesIcons::class.java)

    @JvmField
    val GutterNoteAdd: Icon = IconLoader.getIcon("/icons/noteGutterAdd.svg", NotesIcons::class.java)

    @JvmField
    val NoteBadge: Icon = IconLoader.getIcon("/icons/noteBadge.svg", NotesIcons::class.java)
}
