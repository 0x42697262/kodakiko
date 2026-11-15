package dev.kodakiko.markdown

internal object MarkedRules {
    const val DOLLAR = '$'
    const val MATCH_REFERENCE = '&'
    const val BEFORE_REFERENCE = '`'
    const val AFTER_REFERENCE = '\''
    val CARET = Regex("(^|[^\\[])\\^")

    private const val INDENT_MARK = "INDENT"
    private const val BULLET_MARK = "BULL"
    private const val UNICODE = "u"
    private const val IGNORE_CASE = "i"
    private const val MULTILINE = "m"

    private const val TAG = "address|article|aside|base|basefont|blockquote|body|caption" +
        "|center|col|colgroup|dd|details|dialog|dir|div|dl|dt|fieldset|figcaption" +
        "|figure|footer|form|frame|frameset|h[1-6]|head|header|hr|html|iframe" +
        "|legend|li|link|main|menu|menuitem|meta|nav|noframes|ol|optgroup|option" +
        "|p|param|search|section|summary|table|tbody|td|tfoot|th|thead|title" +
        "|tr|track|ul"

    private const val HR = """^ {0,3}((?:-[\t ]*){3,}+|(?:_[ \t]*){3,}+|(?:\*[ \t]*){3,}+)(?:\n+|$)"""
    private const val BULLET = """ {0,3}(?:[*+-]|\d{1,9}[.)])"""
    private const val HEADING_INTERRUPT = """ {0,3}#{1,6}(?:\s|$)"""
    private const val FENCES_INTERRUPT = """ {0,3}(?:`{3,}(?=[^`\n]*(?:\n|$))|~~~)[^\n]*(?:\n|$)"""
    private const val HTML_INTERRUPT =
        """ {0,3}(?:</?(?:tag)(?: +|\n|/?>)|<(?:(?:script|pre|style|textarea)(?:[\s>]|$)|!--)|<\?|<![A-Za-z]|<!\[CDATA\[)"""
    private const val PARAGRAPH_INTERRUPTS = """hr|heading|lheading|blockquote|fences|list|html|table|[ \t]+\n"""
    private const val BLOCK_LABEL = """(?!\s*\])(?:\\[\s\S]|[^\[\]\\\n]|\n(?![ \t]*\n))++"""
    private const val COMMENT = """<!--(?:-?>|[\s\S]*?(?:-->|$))"""
    private const val LHEADING_START_BLOCKERS = """bull |blockCode|fences|blockquote|heading|html|table"""
    private const val LHEADING_LINE_BLOCKERS = """\s*?\n|bull |fences|blockquote|heading|hr|html|table"""
    private const val LHEADING_TAIL = """\n {0,3}(=+|-+)[ \t]*(?:\n+|$)"""
    private const val NEWLINE = """^(?:[ \t]*(?:\n|$))+"""
    private const val CODE = """^((?: {4}| {0,3}\t)[^\n]+(?:\n(?:[ \t]*(?:\n|$))*)?)+"""
    private const val QUOTE_MARKER = """ {0,3}> ?"""
    private const val BLANK_AHEAD = """[ \t]*(?:\n|$)"""

    private const val PUNCTUATION = """[\p{P}\p{S}]"""
    private const val PUNCTUATION_OR_SPACE = """[\s\p{P}\p{S}]"""
    private const val NOT_PUNCTUATION_OR_SPACE = """[^\s\p{P}\p{S}]"""
    private const val PUNCTUATION_GFM_STRONG_EM = """(?!~)[\p{P}\p{S}]"""
    private const val PUNCTUATION_OR_SPACE_GFM_STRONG_EM = """(?!~)[\s\p{P}\p{S}]"""
    private const val NOT_PUNCTUATION_OR_SPACE_GFM_STRONG_EM = """(?:[^\s\p{P}\p{S}]|~)"""
    private const val INLINE_LABEL_BRACKETS = """\[(?:\\[\s\S]|[^\[\]\\])*+\]"""

    private fun paragraphInterruptsWith(listInterrupt: String): String = MarkedEdit(PARAGRAPH_INTERRUPTS)
        .replace("hr", HR)
        .replace("heading", HEADING_INTERRUPT)
        .replace("|lheading", "")
        .replace("|table", "")
        .replace("blockquote", " {0,3}>")
        .replace("fences", FENCES_INTERRUPT)
        .replace("list", listInterrupt)
        .replace("html", HTML_INTERRUPT)
        .replace("tag", TAG)
        .source()

    private val gfmTable: String = MarkedEdit(
        """^ *([^\n ].*)\n {0,3}((?:\| *)?:?-+:? *(?:\| *:?-+:? *)*(?:\| *)?)(?:\n((?:(?! *\n|hr|heading|blockquote|code|fences|list|html).*(?:\n|$))*+)\n*|$)""",
    )
        .replace("hr", HR)
        .replace("heading", HEADING_INTERRUPT)
        .replace("blockquote", " {0,3}>")
        .replace("code", """(?: {4}| {0,3}\t)[^\n]""")
        .replace("fences", FENCES_INTERRUPT)
        .replace("list", """ {0,3}(?:[*+-]|1[.)])[ \t]""")
        .replace("html", HTML_INTERRUPT)
        .replace("tag", TAG)
        .source()

    val fences = jsRegex("""^ {0,3}(`{3,}(?=[^`\n]*(?:\n|$))|~{3,})([^\n]*)(?:\n|$)(?:|([\s\S]*?)(?:\n|$))(?: {0,3}(?:(?=`)\1`*|(?=~)\1~*) *(?=\n|$)|$)""")
    val hr = jsRegex(HR)
    val heading = jsRegex("""^ {0,3}(#{1,6})(?=\s|$)(.*)(?:\n+|$)""")
    private fun lheadingBlockers(blockers: String): String = MarkedEdit(blockers)
        .replaceAll("bull", BULLET)
        .replaceAll("blockCode", """(?: {4}| {0,3}\t)""")
        .replaceAll("fences", """ {0,3}(?:`{3,}|~{3,})""")
        .replaceAll("blockquote", " {0,3}>")
        .replaceAll("heading", HEADING_INTERRUPT)
        .replaceAll("hr", """ {0,3}(?:(?:-[\t ]*){3,}+|(?:_[ \t]*){3,}+|(?:\*[ \t]*){3,}+)(?:\n+|$)""")
        .replaceAll("html", """ {0,3}<[^\n>]+>\n""")
        .replaceAll("table", """ {0,3}\|?(?:[:\- ]*\|)+[\:\- ]*\n""")
        .source()

    private val lheadingStartBlockerSource = lheadingBlockers(LHEADING_START_BLOCKERS)
    private val lheadingLineBlockerSource = lheadingBlockers(LHEADING_LINE_BLOCKERS)
    val lheadingStartBlocker = jsRegex("(?:$lheadingStartBlockerSource)")
    val lheadingLineBlocker = jsRegex("(?:$lheadingLineBlockerSource)")
    val lheadingTail = jsRegex(LHEADING_TAIL)
    val text = jsRegex("""^[^\n]+""")
    val def = MarkedEdit("""^ {0,3}\[(label)\]: *(?:\n[ \t]*)?([^<\s][^\s]*|<.*?>)(?:(?: +(?:\n[ \t]*)?| *\n[ \t]*)(title))? *(?:\n+|$)""")
        .replace("label", BLOCK_LABEL)
        .replace("title", """(?:"(?:\\[\s\S]|[^"\\])*+"|'(?:\\[\s\S]|[^'\\\n]|\n(?!\n))*+'|\((?:\\[\s\S]|[^()\\])*+\))""")
        .regex()
    val list = MarkedEdit("""^(bull)([ \t][^\n]*?)?(?:\n|$)""").replaceAll("bull", BULLET).regex()
    val html = MarkedEdit(
        "^ {0,3}(?:" +
            """<(script|pre|style|textarea)[\s>][\s\S]*?(?:</\1>[^\n]*\n*|$)""" +
            """|comment[^\n]*(\n+|$)""" +
            """|<\?[\s\S]*?(?:\?>[^\n]*\n*|$)""" +
            """|<![A-Z][\s\S]*?(?:>[^\n]*\n*|$)""" +
            """|<!\[CDATA\[[\s\S]*?(?:\]\]>[^\n]*\n*|$)""" +
            """|</?(tag)(?:[ \t]+|\n|/?>|$)[\s\S]*?(?:(?:\n[ ${"\t"}]*)+\n|$)""" +
            """|<(?!(?:script|pre|style|textarea)(?![a-z0-9-]))([a-z][a-z0-9-]*)(?:attribute)*? */?>(?=[ \t]*(?:\n|$))[\s\S]*?(?:(?:\n[ ${"\t"}]*)+\n|$)""" +
            """|</[a-z][a-z0-9-]*\s*>(?=[ \t]*(?:\n|$))[\s\S]*?(?:(?:\n[ ${"\t"}]*)+\n|$)""" +
            ")",
        IGNORE_CASE,
    )
        .replace("comment", COMMENT)
        .replace("tag", TAG)
        .replace("attribute", """ +[a-zA-Z:_][\w.:-]*(?: *= *"[^"\n]*"| *= *'[^'\n]*'| *= *[^\s"'=<>`]+)?""")
        .regex()
    val table = jsRegex(gfmTable)
    private val paragraphInterruptSource: String = MarkedEdit(PARAGRAPH_INTERRUPTS)
        .replace("hr", HR)
        .replace("heading", HEADING_INTERRUPT)
        .replace("|lheading", "")
        .replace("table", gfmTable)
        .replace("blockquote", " {0,3}>")
        .replace("fences", FENCES_INTERRUPT)
        .replace("list", """ {0,3}(?:[*+-]|1[.)])[ \t]+[^ \t\n]""")
        .replace("html", HTML_INTERRUPT)
        .replace("tag", TAG)
        .source()
    private val blockquoteParagraphInterruptSource = paragraphInterruptsWith(""" {0,3}(?:[*+-]|\d{1,9}[.)])(?:[ \t]|\n|$)""")
    val paragraphInterrupt = jsRegex("(?:$paragraphInterruptSource)")
    val blockquoteParagraphInterrupt = jsRegex("(?:$blockquoteParagraphInterruptSource)")
    val quoteMarker = jsRegex(QUOTE_MARKER)
    val blankAhead = jsRegex(BLANK_AHEAD)

    private fun paragraphSourceWith(interrupts: String): String = """^([^\n]+(?:\n(?!$interrupts)[^\n]+)*)"""

    val referenceSources: Map<String, String> by lazy {
        mapOf(
            "newline" to NEWLINE,
            "code" to CODE,
            "paragraph" to paragraphSourceWith(paragraphInterruptSource),
            "blockquote" to """^($QUOTE_MARKER(?:(?!$BLANK_AHEAD)(${paragraphSourceWith(blockquoteParagraphInterruptSource).removePrefix("^")})|[^\n]*)(?:\n|$))+""",
            "lheading" to """^(?!$lheadingStartBlockerSource)((?:.|\n(?!$lheadingLineBlockerSource))+?)$LHEADING_TAIL""",
        )
    }

    val escape = jsRegex("""^\\([!"#$%&'()*+,\-./:;<=>?@\[\]\\^_`{|}~])""")
    val inlineCode = jsRegex("""^(`+)([^`]|[^`][\s\S]*?[^`])\1(?!`)""")
    val br = MarkedEdit("""^( {2,}|\\)\n(?!\s*$)[ \t]*""").replace("{2,}", "*").regex()
    val punctuation = MarkedEdit("""^((?![*_])punctSpace)""", UNICODE).replaceAll("punctSpace", PUNCTUATION_OR_SPACE).regex()
    val blockSkip = MarkedEdit("link|precode-code|html", "g")
        .replace("link", """\[(?:[^\[\]`]++|(?<a>`+)[^`]+\k<a>(?!`))*?\]\((?:\\[\s\S]|[^\\\(\)]|\((?:\\[\s\S]|[^\\\(\)])*+\))*+\)""")
        .replace("precode-", "(?<!`)()")
        .replace("code", """(?<b>`+)[^`]+\k<b>(?!`)""")
        .replace("html", """<(?! )[^<>]*?>""")
        .regex()
    val emStrongLDelim = MarkedEdit("""^(?:\*+(?:((?!\*)punct)|([^\s*]))?)|^_+(?:((?!_)punct)|([^\s_]))?""", UNICODE)
        .replaceAll("punct", PUNCTUATION_GFM_STRONG_EM)
        .regex()
    val emStrongRDelimAst = MarkedEdit(
        """^[^_*]*?__[^_*]*?\*[^_*]*?(?=__)""" +
            """|[^*]+(?=[^*])""" +
            """|(?!\*)punct(\*+)(?=[\s]|$)""" +
            """|notPunctSpace(\*+)(?!\*)(?=punctSpace|$)""" +
            """|(?!\*)punctSpace(\*+)(?=notPunctSpace)""" +
            """|[\s](\*+)(?!\*)(?=punct)""" +
            """|(?!\*)punct(\*+)(?!\*)(?=punct)""" +
            """|notPunctSpace(\*+)(?=notPunctSpace)""",
        UNICODE,
    )
        .replaceAll("notPunctSpace", NOT_PUNCTUATION_OR_SPACE_GFM_STRONG_EM)
        .replaceAll("punctSpace", PUNCTUATION_OR_SPACE_GFM_STRONG_EM)
        .replaceAll("punct", PUNCTUATION_GFM_STRONG_EM)
        .regex()
    val emStrongRDelimUnd = MarkedEdit(
        """^[^_*]*?\*\*[^_*]*?_[^_*]*?(?=\*\*)""" +
            """|[^_]+(?=[^_])""" +
            """|(?!_)punct(_+)(?=[\s]|$)""" +
            """|notPunctSpace(_+)(?!_)(?=punctSpace|$)""" +
            """|(?!_)punctSpace(_+)(?=notPunctSpace)""" +
            """|[\s](_+)(?!_)(?=punct)""" +
            """|(?!_)punct(_+)(?!_)(?=punct)""",
        UNICODE,
    )
        .replaceAll("notPunctSpace", NOT_PUNCTUATION_OR_SPACE)
        .replaceAll("punctSpace", PUNCTUATION_OR_SPACE)
        .replaceAll("punct", PUNCTUATION)
        .regex()
    val delLDelim = MarkedEdit("""^~~?(?:((?!~)punct)|[^\s~])""", UNICODE).replaceAll("punct", PUNCTUATION).regex()
    val delRDelim = MarkedEdit(
        """^[^~]+(?=[^~])""" +
            """|(?!~)punct(~~?)(?=[\s]|$)""" +
            """|notPunctSpace(~~?)(?!~)(?=punctSpace|$)""" +
            """|(?!~)punctSpace(~~?)(?=notPunctSpace)""" +
            """|[\s](~~?)(?!~)(?=punct)""" +
            """|(?!~)punct(~~?)(?!~)(?=punct)""" +
            """|notPunctSpace(~~?)(?=notPunctSpace)""",
        UNICODE,
    )
        .replaceAll("notPunctSpace", NOT_PUNCTUATION_OR_SPACE)
        .replaceAll("punctSpace", PUNCTUATION_OR_SPACE)
        .replaceAll("punct", PUNCTUATION)
        .regex()
    val anyPunctuation = MarkedEdit("""\\(punct)""", UNICODE).replaceAll("punct", PUNCTUATION).regex()
    val anyPunctuationOutsideCode = MarkedEdit("""(`+)(?:[^`]|[^`][\s\S]*?[^`])\1(?!`)|`+|\\(punct)""", UNICODE)
        .replaceAll("punct", PUNCTUATION)
        .regex()
    val autolink = MarkedEdit("""^<(scheme:[^\s\x00-\x1f<>]*|email)>""")
        .replace("scheme", """[a-zA-Z][a-zA-Z0-9+.-]{1,31}""")
        .replace(
            "email",
            """[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+(@)[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+(?![-_])""",
        )
        .regex()
    val tag = MarkedEdit(
        "^comment" +
            """|^</[a-zA-Z][a-zA-Z0-9-]*\s*>""" +
            """|^<[a-zA-Z][a-zA-Z0-9-]*(?:attribute)*?\s*/?>""" +
            """|^<\?[\s\S]*?\?>""" +
            """|^<![a-zA-Z]+\s[\s\S]*?>""" +
            """|^<!\[CDATA\[[\s\S]*?\]\]>""",
    )
        .replace("comment", MarkedEdit(COMMENT).replace("(?:-->|$)", "-->").source())
        .replace("attribute", """\s+[a-zA-Z:_][\w.:-]*(?:\s*=\s*"[^"]*"|\s*=\s*'[^']*'|\s*=\s*[^\s"'=<>`]+)?""")
        .regex()

    private val inlineLabel: String = MarkedEdit("""(?:\[(?:brackets|\\[\s\S]|[^\[\]\\])*+\]|\\[\s\S]|`+(?!`)[^`]*?`+(?!`)|``+(?=\])|[^\[\]\\`]++)*?""")
        .replace("brackets", INLINE_LABEL_BRACKETS)
        .source()

    val link = MarkedEdit("""^!?\[(label)\]\([ \t\n]*(href)(?:(?:[ \t]+(?:\n[ \t]*)?|\n[ \t]*)(title))?[ \t\n]*\)""")
        .replace("label", inlineLabel)
        .replace("href", """<(?:\\.|[^\n<>\\])++>|[^ \t\n\x00-\x1f]+|(?=\))""")
        .replace("title", """"(?:\\[\s\S]|[^"\\])*+"|'(?:\\[\s\S]|[^'\\])*+'|\((?:\\[\s\S]|[^)\\])*+\)""")
        .regex()
    val reflink = MarkedEdit("""^!?\[(label)\]\[(ref)\]""").replace("label", inlineLabel).replace("ref", BLOCK_LABEL).regex()
    val nolink = MarkedEdit("""^!?\[(ref)\](?:\[\])?""").replace("ref", BLOCK_LABEL).regex()

    private const val BOUNDED_BLOCK_LABEL = """(?!\s*\])(?:\\[\s\S]|[^\[\]\\]){1,999}"""
    private val boundedInlineLabel: String =
        MarkedEdit("""(?:[^\[\]\\`]*(?:\[(?:brackets|\\[\s\S]|[^\[\]\\])*\]|\\[\s\S]|`+(?!`)[^`]*?`+(?!`)|``+(?=\]))){0,999}?[^\[\]\\`]*?""")
            .replace("brackets", INLINE_LABEL_BRACKETS)
            .source()

    val reflinkSearch = MarkedEdit("""reflink|nolink(?!\()""", "g")
        .replace("reflink", MarkedEdit("""^!?\[(label)\]\[(ref)\]""").replace("label", boundedInlineLabel).replace("ref", BOUNDED_BLOCK_LABEL).source())
        .replace("nolink", MarkedEdit("""^!?\[(ref)\](?:\[\])?""").replace("ref", BOUNDED_BLOCK_LABEL).source())
        .regex()

    private const val CASE_INSENSITIVE_PROTOCOL = """[hH][tT][tT][pP][sS]?|[fF][tT][pP]"""
    private const val EXTENDED_EMAIL = """[A-Za-z0-9._+-]+@[a-zA-Z0-9-_]+(?:\.[a-zA-Z0-9-_]*[a-zA-Z0-9])+(?![\w-])"""

    val url = MarkedEdit("""^emailProtocol|^((?:protocol):\/\/|www\.)(?:[a-zA-Z0-9\-]+\.?)++[^\s<]*|^email""")
        .replace(
            "emailProtocol",
            MarkedEdit("""(?:mailto:email|xmpp:email(?:\/[A-Za-z0-9@.]+)?)""").replaceAll("email", EXTENDED_EMAIL).source(),
        )
        .replace("protocol", CASE_INSENSITIVE_PROTOCOL)
        .replace("email", """[A-Za-z0-9._+-]+(@)[a-zA-Z0-9-_]+(?:\.[a-zA-Z0-9-_]*[a-zA-Z0-9])+(?![\w-])""")
        .regex()
    val backpedal = jsRegex("""(?:[^?!.,:;*_'"~()&]+|\([^)]*\)|&(?![a-zA-Z0-9]+;$)|[?!.,:;*_'"~)]+(?!$))++""")

    private val gfmText: String = MarkedEdit(
        """^(?:[^a-zA-Z0-9](?=emailProtocol)|(`+|~+|[^`~])(?:(?=[`~])|(?= {2,}\n)|(?=[a-zA-Z0-9.!#$%&'*+\/=?_`{\|}~-]+@)|[\s\S]*?(?:(?=[\\<!\[`*~_]|\b_|protocol:\/\/|wwwStart|$)|[^ ](?= {2,}\n)|[^a-zA-Z0-9](?=emailProtocol)|[^a-zA-Z0-9.!#$%&'*+\/=?_`{\|}~-](?=[a-zA-Z0-9.!#$%&'*+\/=?_`{\|}~-]+@))))""",
    )
        .replace("protocol", CASE_INSENSITIVE_PROTOCOL)
        .replaceAll("emailProtocol", """(?:mailto|xmpp):""")
        .replace("wwwStart", """(?<![^\s*_~(])www\.""")
        .source()

    val inlineText = MarkedEdit(gfmText).replace("""\b_""", """\b_| {2,}\n""").replaceAll("{2,}", "*").regex()

    val codeRemoveIndent = jsRegex("""^(?: {0,3}\t| {1,4})""", MULTILINE)
    val outputLinkReplace = jsRegex("""\\([\[\]])""")
    val indentCodeCompensation = jsRegex("""^(\s+)(?:```)""")
    val beginningSpace = jsRegex("""^\s+""")
    val endingHash = jsRegex("""#$""")
    val startingSpaceChar = jsRegex("""^ """)
    val endingSpaceChar = jsRegex(""" $""")
    val endingSpaceTabChar = jsRegex("""[ \t]$""")
    val nonSpaceChar = jsRegex("""[^ ]""")
    val leadingSpaceTab = jsRegex("""^[ \t]+""")
    val multipleSpace = jsRegex("""\s+""")
    val blankLine = jsRegex("""^[ \t]*$""")
    val doubleBlankLine = jsRegex("""\n[ \t]*\n[ \t]*$""")
    val blockquoteStart = jsRegex("""^ {0,3}>""")
    val blockquoteSetextReplace = jsRegex("""\n {0,3}((?:=+|-+)[ \t]*)(?=\n|$)""")
    val blockquoteSetextReplace2 = jsRegex("""^ {0,3}>[ \t]?""", MULTILINE)
    val blockquoteMarkerWhitespace = jsRegex("""^( {0,3}>)([ \t]+)""", MULTILINE)
    val listIsTask = jsRegex("""^\[[ xX]\][ \t]+\S""")
    val listReplaceTask = jsRegex("""^\[[ xX]\][ \t]+""")
    val listTaskCheckbox = jsRegex("""\[[ xX]\]""")
    val anyLine = jsRegex("""\n.*\n""")
    val hrefBrackets = jsRegex("""^<(.*)>$""")
    val tableDelimiter = jsRegex("""[:|]""")
    val tableAlignChars = jsRegex("""^\||\| *$""")
    val tableRowBlankLine = jsRegex("""\n[ \t]*$""")
    val tableAlignRight = jsRegex("""^ *-+: *$""")
    val tableAlignCenter = jsRegex("""^ *:-+: *$""")
    val tableAlignLeft = jsRegex("""^ *:-+ *$""")
    val startATag = jsRegex("""^<a """, IGNORE_CASE)
    val endATag = jsRegex("""^<\/a>""", IGNORE_CASE)
    val startPreScriptTag = jsRegex("""^<(pre|code|kbd|script)(\s|>)""", IGNORE_CASE)
    val endPreScriptTag = jsRegex("""^<\/(pre|code|kbd|script)(\s|>)""", IGNORE_CASE)
    val startAngleBracket = jsRegex("""^<""")
    val endAngleBracket = jsRegex(""">$""")
    val unicodeAlphaNumeric = jsRegex("""[\p{L}\p{N}]""", UNICODE)
    val numericCharacterReference = jsRegex("""&#(?:(\d{1,7})|[Xx]([A-Fa-f0-9]{1,6}));""")
    val numericCharacterReferenceOrAmpersand = jsRegex("""&#(?:(\d{1,7})|[Xx]([A-Fa-f0-9]{1,6}));|&(?!(#\d{1,7}|#[Xx][a-fA-F0-9]{1,6}|\w+);)""")
    val carriageReturn = jsRegex("""\r\n|\r""")
    val findPipe = jsRegex("""\|""")
    val splitPipe = jsRegex(""" \|""")
    val slashPipe = jsRegex("""\\\|""")
    val setextUnderline = jsRegex("""^ {0,3}(?:=+|-+) *$""")
    val leadingIndent = jsRegex("""^ {0,3}""")
    val leadingNewline = jsRegex("""^\n""")
    val backtick = jsRegex("""`""")

    fun listItem(bullet: String): Regex = jsRegex("""^( {0,3}BULL)((?:[${"\t"} ][^\n]*)?(?:\n|$))""".replace(BULLET_MARK, bullet))

    private fun indentedFamily(template: String, flags: String = ""): List<Regex> =
        (0..MarkedConstants.INDENT_BOUND_MAX).map { bound -> jsRegex(template.replace(INDENT_MARK, bound.toString()), flags) }

    private fun bounded(family: List<Regex>, indent: Int): Regex = family[maxOf(0, minOf(MarkedConstants.INDENT_BOUND_MAX, indent - 1))]

    private val nextBulletFamily = indentedFamily("""^ {0,INDENT}(?:[*+-]|\d{1,9}[.)])((?:[ ${"\t"}][^\n]*)?(?:\n|$))""")
    private val hrFamily = indentedFamily("""^ {0,INDENT}((?:-[ ${"\t"}]*){3,}+|(?:_[ ${"\t"}]*){3,}+|(?:\*[ ${"\t"}]*){3,}+)(?:\n+|$)""")
    private val fencesFamily = indentedFamily("""^ {0,INDENT}(?:```|~~~)""")
    private val headingFamily = indentedFamily("""^ {0,INDENT}#{1,6}(?:\s|$)""")
    private val htmlFamily = indentedFamily(
        """^ {0,INDENT}(?:</?(?:$TAG)(?: +|$|/?>)|<(?:(?:script|pre|style|textarea)(?:[\s>]|$)|!--)|<\?|<![A-Za-z]|<!\[CDATA\[)""",
        IGNORE_CASE,
    )
    private val blockquoteFamily = indentedFamily("""^ {0,INDENT}>""")

    fun nextBullet(indent: Int): Regex = bounded(nextBulletFamily, indent)

    fun hrBegin(indent: Int): Regex = bounded(hrFamily, indent)

    fun fencesBegin(indent: Int): Regex = bounded(fencesFamily, indent)

    fun headingBegin(indent: Int): Regex = bounded(headingFamily, indent)

    fun htmlBegin(indent: Int): Regex = bounded(htmlFamily, indent)

    fun blockquoteBegin(indent: Int): Regex = bounded(blockquoteFamily, indent)
}
