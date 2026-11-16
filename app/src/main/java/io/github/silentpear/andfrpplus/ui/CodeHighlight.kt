package io.github.silentpear.andfrpplus.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** 支持高亮的语言种类，按扩展名判断。 */
enum class CodeLang { KOTLIN, JAVA, JSON, YAML, XML, SHELL, PYTHON, JS, MARKDOWN, PROPERTIES, PLAIN }

fun detectLanguage(fileName: String): CodeLang = when (fileName.substringAfterLast('.', "").lowercase()) {
    "kt", "kts" -> CodeLang.KOTLIN
    "java" -> CodeLang.JAVA
    "json" -> CodeLang.JSON
    "yml", "yaml" -> CodeLang.YAML
    "xml", "html", "htm", "svg" -> CodeLang.XML
    "sh", "bash", "zsh" -> CodeLang.SHELL
    "py" -> CodeLang.PYTHON
    "js", "mjs", "ts", "jsx", "tsx" -> CodeLang.JS
    "md", "markdown" -> CodeLang.MARKDOWN
    "properties", "conf", "cfg", "ini", "toml", "env" -> CodeLang.PROPERTIES
    else -> CodeLang.PLAIN
}

data class CodeColors(
    val normal: Color,
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val punctuation: Color
)

private fun keywordsFor(lang: CodeLang): Set<String> = when (lang) {
    CodeLang.KOTLIN -> setOf(
        "package", "import", "fun", "val", "var", "class", "object", "interface", "data",
        "sealed", "enum", "if", "else", "when", "for", "while", "do", "return", "break",
        "continue", "try", "catch", "finally", "throw", "null", "true", "false", "is", "as",
        "in", "out", "private", "public", "internal", "protected", "override", "open",
        "abstract", "companion", "init", "constructor", "suspend", "lateinit", "by", "this",
        "super", "typealias", "inline", "reified", "crossinline", "const", "object"
    )
    CodeLang.JAVA -> setOf(
        "package", "import", "class", "interface", "enum", "extends", "implements", "public",
        "private", "protected", "static", "final", "void", "int", "long", "double", "float",
        "boolean", "char", "byte", "short", "new", "return", "if", "else", "for", "while",
        "do", "switch", "case", "break", "continue", "try", "catch", "finally", "throw",
        "throws", "null", "true", "false", "this", "super", "abstract", "synchronized"
    )
    CodeLang.JS -> setOf(
        "const", "let", "var", "function", "return", "if", "else", "for", "while", "do",
        "class", "extends", "new", "import", "export", "from", "default", "async", "await",
        "try", "catch", "finally", "throw", "null", "undefined", "true", "false", "this",
        "typeof", "instanceof", "interface", "type", "enum", "as", "of", "in"
    )
    CodeLang.PYTHON -> setOf(
        "def", "class", "return", "if", "elif", "else", "for", "while", "import", "from",
        "as", "try", "except", "finally", "raise", "with", "lambda", "None", "True", "False",
        "and", "or", "not", "in", "is", "pass", "yield", "async", "await", "global", "self"
    )
    CodeLang.SHELL -> setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac",
        "function", "return", "export", "local", "echo", "exit", "set", "source"
    )
    CodeLang.YAML, CodeLang.PROPERTIES, CodeLang.JSON, CodeLang.XML, CodeLang.MARKDOWN, CodeLang.PLAIN ->
        emptySet()
}

private fun lineCommentToken(lang: CodeLang): String? = when (lang) {
    CodeLang.KOTLIN, CodeLang.JAVA, CodeLang.JS -> "//"
    CodeLang.SHELL, CodeLang.PYTHON, CodeLang.YAML, CodeLang.PROPERTIES -> "#"
    else -> null
}

private fun supportsBlockComment(lang: CodeLang): Boolean =
    lang == CodeLang.KOTLIN || lang == CodeLang.JAVA || lang == CodeLang.JS

/** 把文本切成着色片段；只做词法级别的高亮，不追求语法正确。 */
fun highlightCode(text: String, lang: CodeLang, colors: CodeColors): AnnotatedString {
    if (lang == CodeLang.PLAIN) return AnnotatedString(text)

    val keywords = keywordsFor(lang)
    val lineComment = lineCommentToken(lang)
    val blockComments = supportsBlockComment(lang)
    val builder = AnnotatedString.Builder(text)

    val length = text.length
    var i = 0
    while (i < length) {
        val c = text[i]

        // 行注释
        if (lineComment != null && c == lineComment[0] &&
            text.regionMatches(i, lineComment, 0, lineComment.length)
        ) {
            val end = text.indexOf('\n', i).let { if (it < 0) length else it }
            builder.addStyle(SpanStyle(color = colors.comment), i, end)
            i = end
            continue
        }

        // 块注释
        if (blockComments && c == '/' && i + 1 < length && text[i + 1] == '*') {
            val end = text.indexOf("*/", i + 2).let { if (it < 0) length else it + 2 }
            builder.addStyle(SpanStyle(color = colors.comment), i, end)
            i = end
            continue
        }

        // XML 注释
        if (lang == CodeLang.XML && text.startsWith("<!--", i)) {
            val end = text.indexOf("-->", i + 4).let { if (it < 0) length else it + 3 }
            builder.addStyle(SpanStyle(color = colors.comment), i, end)
            i = end
            continue
        }

        // 字符串
        if (c == '"' || c == '\'' || (lang == CodeLang.JS && c == '`')) {
            var j = i + 1
            while (j < length) {
                if (text[j] == '\\') {
                    j += 2
                    continue
                }
                if (text[j] == c) {
                    j++
                    break
                }
                if (text[j] == '\n' && c != '`') break
                j++
            }
            builder.addStyle(SpanStyle(color = colors.string), i, j.coerceAtMost(length))
            i = j
            continue
        }

        // 数字
        if (c.isDigit() && (i == 0 || !text[i - 1].isLetterOrDigit() && text[i - 1] != '_')) {
            var j = i
            while (j < length && (text[j].isDigit() || text[j] == '.' || text[j] == 'x'
                        || text[j] in 'a'..'f' || text[j] in 'A'..'F' || text[j] == 'L')
            ) j++
            builder.addStyle(SpanStyle(color = colors.number), i, j)
            i = j
            continue
        }

        // 标识符 / 关键字
        if (c.isLetter() || c == '_') {
            var j = i
            while (j < length && (text[j].isLetterOrDigit() || text[j] == '_')) j++
            val word = text.substring(i, j)
            if (keywords.contains(word)) {
                builder.addStyle(
                    SpanStyle(color = colors.keyword, fontWeight = FontWeight.Medium),
                    i, j
                )
            } else if (lang == CodeLang.YAML && j < length && text[j] == ':') {
                builder.addStyle(SpanStyle(color = colors.keyword), i, j)
            }
            i = j
            continue
        }

        if (c in "{}[]()<>=+-*/%!&|:;,") {
            builder.addStyle(SpanStyle(color = colors.punctuation), i, i + 1)
        }
        i++
    }
    return builder.toAnnotatedString()
}

class CodeHighlightTransformation(
    private val lang: CodeLang,
    private val colors: CodeColors
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        return TransformedText(
            highlightCode(text.text, lang, colors),
            OffsetMapping.Identity
        )
    }
}
