package dev.kodakiko.markdown

const val MARKDOWN_MAX_DEPTH = 6
const val MAX_HEADING_LEVEL = 3
val SAFE_LINK_SCHEMES: Set<String> = linkedSetOf("http:", "https:", "mailto:")

internal object ParseConstants {
    const val MIN_HEADING_LEVEL = 1
    const val UNORDERED_START = 1L
    const val SCHEME_GROUP = 1
    val SCHEME_PATTERN = Regex("^([a-z][a-z0-9+.-]*:)")
}

internal object ToolbarConstants {
    const val BOLD = "**"
    const val ITALIC = "*"
    const val STRIKE = "~~"
    const val BULLET = "- "
    const val CHECKLIST = "- [ ] "
    const val QUOTE = "> "
    const val HEADING_ONE = "# "
    const val HEADING_TWO = "## "
    const val LINK_URL = "url"
    const val LINK_LABEL_START = "["
    const val LINK_URL_START = "]("
    const val LINK_URL_END = ")"
    const val LINE_BREAK = "\n"
    const val NOT_FOUND = -1
}

internal object JsRegexConstants {
    const val BACKSLASH = '\\'
    const val CLASS_START = '['
    const val CLASS_END = ']'
    const val CLASS_NEGATION = '^'
    const val RANGE = '-'
    const val NEGATED_CLASS_START_DISTANCE = 2
    const val AMPERSAND = '&'
    const val ANY = '.'
    const val START = '^'
    const val END = '$'
    const val BRACE_OPEN = '{'
    const val BRACE_CLOSE = '}'
    const val VERTICAL_TAB_ESCAPE = 'v'
    const val BOUNDARY_ESCAPE = 'b'
    const val UNICODE_PROPERTY_ESCAPE = 'p'
    const val UNICODE_PROPERTY_NEGATED_ESCAPE = 'P'
    const val VERTICAL_TAB = "\\x0B"
    const val BACKSPACE = "\\x08"
    const val MULTILINE_FLAG = 'm'
    const val IGNORE_CASE_FLAG = 'i'
    private const val WHITESPACE = "\\t\\n\\x0B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private const val WORD = "A-Za-z0-9_"
    private const val DIGIT = "0-9"
    private const val LINE_TERMINATORS = "[\\n\\r\\u2028\\u2029]"
    private const val WORD_CLASS = "[$WORD]"
    const val JS_DOT = "[^\\n\\r\\u2028\\u2029]"
    const val JS_INPUT_END = "\\z"
    const val JS_LINE_END = "(?=$LINE_TERMINATORS|\\z)"
    const val JS_LINE_START = "(?:\\A|(?<=$LINE_TERMINATORS))"
    val QUANTIFIER_PATTERN = Regex("\\{[0-9]+(?:,[0-9]*)?\\}")
    val ESCAPES: Map<Char, String> = mapOf(
        's' to "[$WHITESPACE]",
        'S' to "[^$WHITESPACE]",
        'w' to WORD_CLASS,
        'W' to "[^$WORD]",
        'd' to "[$DIGIT]",
        'D' to "[^$DIGIT]",
        'b' to "(?:(?<=$WORD_CLASS)(?!$WORD_CLASS)|(?<!$WORD_CLASS)(?=$WORD_CLASS))",
        'B' to "(?:(?<=$WORD_CLASS)(?=$WORD_CLASS)|(?<!$WORD_CLASS)(?!$WORD_CLASS))",
        '/' to "/",
    )
    val CLASS_ESCAPES: Map<Char, String> = mapOf(
        's' to WHITESPACE,
        'S' to "[^$WHITESPACE]",
        'w' to WORD,
        'W' to "[^$WORD]",
        'd' to DIGIT,
        'D' to "[^$DIGIT]",
        '/' to "/",
    )
}

internal object MarkedConstants {
    const val INDENT_BOUND_MAX = 3
    const val TAB_STOP = 4
    const val INDENTED_CODE_INDENT = 4
    const val ITEM_INDENT_MAX = 4
    const val TAB_SPACES = "    "
    const val SETEXT_CONTINUATION_INDENT = "    "
    const val ORDERED_START_DELIMITER_LENGTH = 1
    const val LINK_PREFIX_LENGTH = 4
    const val IMAGE_LINK_PREFIX_LENGTH = 5
    const val EMPHASIS_RULE_MODULUS = 3
    const val STRONG_MARKER_LENGTH = 2
    const val MAX_CODE_POINT = 0x10FFFF
    const val AMPERSAND_CODE = 38
    const val HEX_RADIX = 16
    const val DECIMAL_RADIX = 10
    const val CLOSING_BRACKET_UNBALANCED = -2
    const val NOT_FOUND = -1
    const val PAIR = 2
    const val NEWLINE = '\n'
    const val NEWLINE_TEXT = "\n"
    const val SPACE = ' '
    const val TAB = '\t'
    const val HASH = '#'
    const val BACKSLASH = '\\'
    const val BACKTICK = '`'
    const val LESS_THAN = '<'
    const val BANG = '!'
    const val OPEN_BRACKET = '['
    const val OPEN_BRACKET_TEXT = "["
    const val LINK_SEPARATOR = "]["
    const val CLOSE_BRACKET_TEXT = "]"
    const val MASK_CHAR = 'a'
    const val PUNCTUATION_MASK = '+'
    const val PUNCTUATION_MASK_TEXT = "+"
    const val PIPE = '|'
    const val PIPE_TEXT = "|"
    const val SPACED_PIPE = " |"
    const val PARENTHESES = "()"
    const val ASTERISK = '*'
    const val UNDERSCORE = '_'
    const val TILDE = '~'
    const val EQUALS = '='
    const val UNCHECKED_BOX = "[ ]"
    const val AMPERSAND_ENTITY = "&amp;"
    const val MAILTO = "mailto:"
    const val WWW = "www."
    const val HTTP = "http://"
    const val CLOSE_PAREN = ")"
    const val INFINITE_LOOP = "Infinite loop on byte: "
    const val REPLACEMENT_CHARACTER = '\uFFFD'
    const val SUPPLEMENTARY_BASE = 0x10000
    const val SURROGATE_SHIFT = 10
    const val SURROGATE_MASK = 0x3FF
    const val ORDERED_BULLET_PATTERN = "\\d{1,9}"
    const val DELIMITER_GROUPS = 6
    const val LEFT_ONLY_PUNCT_GROUP = 3
    const val LEFT_ONLY_GROUP = 4
    const val EITHER_PUNCT_GROUP = 5
    const val EITHER_GROUP = 6
    const val AT = "@"
    const val LINE_TERMINATORS = "\n\r\u2028\u2029"
    const val CLOSER_BLOCKERS = " \t\n\r\u000C"
}

internal object RegexGroups {
    const val SECOND = 2
    const val THIRD = 3
    const val FOURTH = 4
}
