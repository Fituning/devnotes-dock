# JetBrains Notes & TODOs Plugin

Plugin de prise de notes et d'organisation hiérarchique avec intégration des **TODOs**, conçu pour **WebStorm** et l'ensemble de la suite **JetBrains** (IntelliJ IDEA, PyCharm, PhpStorm, GoLand, CLion, etc.).

---

## 🎯 Fonctionnalités Clés

1. **Intégration Barre Latérale Droite (`ToolWindow`)** :
   - Un volet **Notes** toujours accessible en 1 clic dans la barre droite de l'IDE.
   - Ne gêne pas l'espace de code principal.

2. **3 Vues Complètes & Dédiées** :
   - **Notes Projet** :
     - Notes stockées localement dans `.idea/notes/` à la racine de votre projet.
     - Totalement isolées : chaque projet dispose de son propre espace de travail documentaire, sans polluer vos commits Git (déjà ignoré ou rangé dans `.idea`).
     - Bouton d'export vers les Notes Globales en un clic.
   - **Notes Globales** :
     - Notes partagées enregistrées dans `~/.jetbrains-notes/`.
     - Accessibles peu importe le projet ou l'IDE JetBrains ouvert.
     - Permet d'avoir une base de connaissances personnelle, des mémos, des snippets, et d'importer une note globale vers le projet actuel.
   - **TODOs & Notes** :
     - Détecte automatiquement les commentaires `TODO` et `FIXME` dans le projet.
     - Permet d'écrire et sauvegarder une note détaillée pour chaque TODO.
     - Bouton direct **"Aller au code"** pour ouvrir le fichier exactement à la ligne concernée.
     - **Indicateur dans l'éditeur (Gutter Icon)** : Une icône spéciale apparaît dans la marge gauche du code devant les lignes contenant un TODO annoté. Un clic sur l'icône permet de voir et modifier la note directement !

3. **Arborescence & Hiérarchie (Dossiers)** :
   - Structure arborescente libre (Dossiers et sous-dossiers).
   - Outils de gestion complets : Créer Note, Créer Dossier, Renommer, Supprimer, Rafraîchir.

4. **Support Markdown & Tâches à cocher** :
   - Format `.md` par défaut.
   - Boutons de mise en forme rapide : Gras, Italique, Titres (H1, H2, H3), Blocs de code, Checkboxes.
   - **Mode Aperçu Interactif** : Les cases à cocher `- [ ]` et `- [x]` peuvent être cochées/décochées en direct dans l'aperçu et mettent à jour le fichier automatiquement !
   - Auto-sauvegarde continue.

---

## 🚀 Utilisation & Commandes

### 1. Tester le plugin en direct dans un IDE JetBrains
Lancez la commande suivante à la racine du projet :
```bash
./gradlew runIde
```
> Cela télécharge l'environnement de bac à sable JetBrains et lance une instance d'IDE avec le plugin déjà activé dans la barre de droite.

### 2. Compiler le package distribuable (`.zip`)
Pour créer le plugin installable dans vos IDE habituels :
```bash
./gradlew buildPlugin
```
Le fichier d'installation sera généré dans :
```
build/distributions/jetbrains-notes-plugin-1.0.0.zip
```

### 3. Installer le plugin dans WebStorm / IntelliJ
1. Ouvrez WebStorm (ou IntelliJ, PyCharm...).
2. Allez dans **Settings** (ou `Cmd + ,` sur macOS) > **Plugins**.
3. Cliquez sur l'icône d'engrenage ⚙️ en haut à droite > **Install Plugin from Disk...**.
4. Sélectionnez le fichier `jetbrains-notes-plugin-1.0.0.zip` situé dans `build/distributions/`.
5. Redémarrez l'IDE si demandé.
6. L'onglet **Notes** apparaît immédiatement dans la barre latérale droite !

---

## 🛠️ Architecture du code

```
jetbrains-notes-plugin/
├── src/main/
│   ├── kotlin/com/plugin/notes/
│   │   ├── model/
│   │   │   └── Models.kt                  # Modèles de données (NoteFileItem, TodoItemModel)
│   │   ├── services/
│   │   │   ├── GlobalNotesService.kt      # Gestion des notes ~/.jetbrains-notes/
│   │   │   ├── ProjectNotesService.kt     # Gestion des notes <project>/.notes/
│   │   │   └── TodoNotesService.kt        # Détection TODOs et persistance des notes associées
│   │   ├── editor/
│   │   │   └── TodoNoteLineMarkerProvider.kt # Icône dans la gouttière de l'éditeur de code
│   │   └── ui/
│   │       ├── NotesIcons.kt              # Icônes SVG du plugin
│   │       ├── BaseNotesPanel.kt          # Volet d'arborescence + éditeur Markdown interactif
│   │       ├── TodoNotesPanel.kt          # Volet de listing des TODOs et rédaction de notes
│   │       └── NotesToolWindowFactory.kt  # Enregistrement des 3 onglets dans la ToolWindow
│   └── resources/
│       ├── META-INF/
│       │   └── plugin.xml                 # Déclaration du plugin et compatibilité com.intellij.modules.platform
│       └── icons/
│           ├── notesToolWindow.svg        # Icône du volet latéral
│           └── noteGutter.svg             # Icône de gouttière
├── build.gradle.kts                       # Configuration Gradle IntelliJ Platform
└── settings.gradle.kts
```
