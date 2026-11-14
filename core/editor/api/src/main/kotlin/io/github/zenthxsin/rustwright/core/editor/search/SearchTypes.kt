package io.github.zenthxsin.rustwright.core.editor.search

data class SearchOptions(val ignoreCase: Boolean = true, val regex: Boolean = false, val wholeWord: Boolean = false)
data class EditorRegexTemplate(val label: String, val hint: String, val find: String, val replace: String? = null)
