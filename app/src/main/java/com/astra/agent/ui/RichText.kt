package com.astra.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astra.agent.render.Highlight
import com.astra.agent.render.Markdown
import com.astra.agent.render.MdBlock
import com.astra.agent.render.MdBullet
import com.astra.agent.render.MdCode
import com.astra.agent.render.MdHeading
import com.astra.agent.render.MdNumber
import com.astra.agent.render.MdPara
import com.astra.agent.render.MdQuote
import com.astra.agent.render.MdRule
import com.astra.agent.render.MdTable
import com.astra.agent.render.Tk
import kotlinx.coroutines.delay

// ── Clipboard ─────────────────────────────────────────────────────────────────

fun copyToClipboard(ctx: Context, text: String) {
    try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ZENIQ", text))
    } catch (_: Throwable) { }
}

/** Copy button that flips to a green "Copied" tick for a moment. */
@Composable
fun CopyButton(text: String, tint: Color, showLabel: Boolean = true, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val c = LocalIos.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }
    val col by animateColorAsState(if (copied) c.green else tint, tween(180), label = "copyc")
    Row(modifier.clip(RoundedCornerShape(9.dp))
        .pressScale { copyToClipboard(ctx, text); copied = true }
        .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
            contentDescription = "Copy", tint = col, modifier = Modifier.size(15.dp))
        if (showLabel) {
            Spacer(Modifier.width(5.dp))
            Text(if (copied) "Copied" else "Copy", fontSize = 12.sp, color = col, fontWeight = FontWeight.Medium)
        }
    }
}

// ── Code colours ──────────────────────────────────────────────────────────────

class CodePalette(
    val bg: Color, val header: Color, val text: Color, val sub: Color,
    val keyword: Color, val string: Color, val number: Color, val comment: Color,
    val function: Color, val type: Color, val tag: Color, val attr: Color,
    val property: Color, val annot: Color, val operator: Color
)

// GitHub-dark inspired
private val DarkCode = CodePalette(
    bg = Color(0xFF0D1117), header = Color(0xFF161B22), text = Color(0xFFE6EDF3), sub = Color(0xFF8B949E),
    keyword = Color(0xFFFF7B72), string = Color(0xFFA5D6FF), number = Color(0xFF79C0FF), comment = Color(0xFF8B949E),
    function = Color(0xFFD2A8FF), type = Color(0xFFFFA657), tag = Color(0xFF7EE787), attr = Color(0xFF79C0FF),
    property = Color(0xFF79C0FF), annot = Color(0xFFFFA657), operator = Color(0xFFFF9E80)
)

// GitHub-light inspired
private val LightCode = CodePalette(
    bg = Color(0xFFF6F8FA), header = Color(0xFFE9EDF1), text = Color(0xFF1F2328), sub = Color(0xFF656D76),
    keyword = Color(0xFFCF222E), string = Color(0xFF0A3069), number = Color(0xFF0550AE), comment = Color(0xFF6E7781),
    function = Color(0xFF8250DF), type = Color(0xFF953800), tag = Color(0xFF116329), attr = Color(0xFF0550AE),
    property = Color(0xFF0550AE), annot = Color(0xFF953800), operator = Color(0xFFB35900)
)

private fun colorFor(t: Tk, p: CodePalette): Color = when (t) {
    Tk.KEYWORD  -> p.keyword
    Tk.STRING   -> p.string
    Tk.NUMBER   -> p.number
    Tk.COMMENT  -> p.comment
    Tk.FUNCTION -> p.function
    Tk.TYPE     -> p.type
    Tk.TAG      -> p.tag
    Tk.ATTR     -> p.attr
    Tk.PROPERTY -> p.property
    Tk.ANNOT    -> p.annot
    Tk.OPERATOR -> p.operator
}

private fun highlighted(code: String, lang: String, p: CodePalette): AnnotatedString = buildAnnotatedString {
    append(code)
    try {
        for (t in Highlight.tokenize(code, lang)) {
            if (t.start < 0 || t.end > code.length || t.start >= t.end) continue
            val st = if (t.type == Tk.COMMENT) SpanStyle(color = colorFor(t.type, p), fontStyle = FontStyle.Italic)
                     else SpanStyle(color = colorFor(t.type, p))
            addStyle(st, t.start, t.end)
        }
    } catch (_: Throwable) { /* plain text is fine */ }
}

/** A code block like Claude's: language label, Copy button, coloured monospace code, scrolls sideways. */
/** Syntax-coloured text for the code editor. */
fun highlightForEditor(code: String, lang: String, dark: Boolean): AnnotatedString =
    highlighted(code, lang, if (dark) DarkCode else LightCode)

@Composable
fun CodeBlock(lang: String, code: String, modifier: Modifier = Modifier) {
    val c = LocalIos.current
    val pal = if (c.dark) DarkCode else LightCode
    val styled = remember(code, lang, c.dark) { highlighted(code, lang, pal) }
    val blockCtx = LocalContext.current
    var previewPath by remember { mutableStateOf<String?>(null) }
    previewPath?.let { p ->
        SitePreview(p, "Preview", onClose = { previewPath = null },
            onOpenExternal = { com.astra.agent.code.FileStorage.open(blockCtx, java.io.File(p), "text/html") })
    }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(pal.bg)) {
        Row(Modifier.fillMaxWidth().background(pal.header).padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(lang.ifBlank { "code" }.lowercase(), Modifier.weight(1f), fontSize = 12.sp, color = pal.sub,
                fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace, maxLines = 1)
            val canPreview = lang.lowercase().let { it == "html" || it == "htm" || it == "svg" || (it == "xml" && code.contains("<svg")) }
            if (canPreview) {
                Text("\u25B6 Preview", Modifier.clickable {
                    val body = if (code.contains("<html", true) || code.contains("<!doctype", true)) code
                        else "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                            "<style>body{margin:0;padding:12px;font-family:sans-serif}svg{max-width:100%;height:auto}</style></head><body>$code</body></html>"
                    val f = java.io.File(blockCtx.cacheDir, "zeniq_block_preview.html")
                    try { f.writeText(body); previewPath = f.path } catch (_: Exception) {}
                }.padding(horizontal = 10.dp, vertical = 8.dp), fontSize = 12.sp, color = pal.sub, fontWeight = FontWeight.SemiBold)
            }
            CopyButton(code, pal.sub)
        }
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp)) {
            SelectionContainer {
                Text(styled, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 19.sp,
                    softWrap = false, color = pal.text)
            }
        }
    }
}

// ── Markdown text ─────────────────────────────────────────────────────────────

private sealed interface Seg
private class TextSeg(val blocks: List<MdBlock>) : Seg
private class CodeSeg(val block: MdCode) : Seg

private fun segments(blocks: List<MdBlock>): List<Seg> {
    val out = ArrayList<Seg>()
    var cur = ArrayList<MdBlock>()
    for (b in blocks) {
        if (b is MdCode) {
            if (cur.isNotEmpty()) { out.add(TextSeg(cur)); cur = ArrayList() }
            out.add(CodeSeg(b))
        } else cur.add(b)
    }
    if (cur.isNotEmpty()) out.add(TextSeg(cur))
    return out
}

private fun inlineText(s: String, codeBg: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    for (r in Markdown.inline(s)) {
        val deco = when {
            r.link != null -> TextDecoration.Underline
            r.strike       -> TextDecoration.LineThrough
            else           -> null
        }
        val style = SpanStyle(
            color = if (r.link != null) linkColor else Color.Unspecified,
            fontSize = if (r.code) 14.sp else TextUnit.Unspecified,
            fontWeight = if (r.bold) FontWeight.Bold else null,
            fontStyle = if (r.italic) FontStyle.Italic else null,
            fontFamily = if (r.code) FontFamily.Monospace else null,
            textDecoration = deco,
            background = if (r.code) codeBg else Color.Unspecified
        )
        withStyle(style) { append(r.text) }
    }
}

@Composable
private fun BlockView(b: MdBlock, fg: Color, user: Boolean) {
    val c = LocalIos.current
    val codeBg = if (user) Color.White.copy(alpha = 0.22f) else c.line.copy(alpha = 0.55f)
    val link = if (user) Color.White else c.accent
    when (b) {
        is MdHeading -> Text(inlineText(b.text, codeBg, link),
            fontSize = when (b.level) { 1 -> 22.sp; 2 -> 19.sp; else -> 17.sp },
            fontWeight = FontWeight.Bold, color = fg, lineHeight = 26.sp)
        is MdPara -> Text(inlineText(b.text, codeBg, link), fontSize = 16.sp, lineHeight = 23.sp, color = fg)
        is MdBullet -> Row(Modifier.padding(start = (b.depth * 14).dp)) {
            Text("\u2022", Modifier.width(18.dp), fontSize = 16.sp, color = fg)
            Text(inlineText(b.text, codeBg, link), fontSize = 16.sp, lineHeight = 23.sp, color = fg)
        }
        is MdNumber -> Row(Modifier.padding(start = (b.depth * 14).dp)) {
            Text(b.num + ".", Modifier.widthIn(min = 24.dp), fontSize = 16.sp, color = fg, fontWeight = FontWeight.Medium)
            Text(inlineText(b.text, codeBg, link), fontSize = 16.sp, lineHeight = 23.sp, color = fg)
        }
        is MdQuote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(if (user) Color.White.copy(alpha = 0.6f) else c.accent))
            Text(inlineText(b.text, codeBg, link), Modifier.padding(start = 10.dp), fontSize = 16.sp, lineHeight = 23.sp,
                color = fg.copy(alpha = 0.85f), fontStyle = FontStyle.Italic)
        }
        is MdTable -> Column(Modifier.clip(RoundedCornerShape(10.dp)).background(codeBg)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                b.header.forEach { h -> Text(h, Modifier.weight(1f).padding(end = 6.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = fg) }
            }
            b.rows.forEach { r ->
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(fg.copy(alpha = 0.2f)))
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    r.forEach { cell -> Text(inlineText(cell, codeBg, link), Modifier.weight(1f).padding(end = 6.dp), fontSize = 14.sp, color = fg) }
                }
            }
        }
        MdRule -> Box(Modifier.fillMaxWidth().height(0.5.dp).background(fg.copy(alpha = 0.3f)))
        is MdCode -> { /* drawn as a CodeBlock by the caller */ }
    }
}

/**
 * Draws a chat message: normal text in an iOS bubble (markdown aware), code in separate coloured code blocks.
 */
@Composable
fun RichMessage(text: String, user: Boolean) {
    val c = LocalIos.current
    // Long answers are parsed on a background thread, so streaming a long text can never freeze the screen.
    val segs: List<Seg> = if (text.length < 1200) remember(text) { segments(Markdown.parse(text)) }
    else {
        val parsed by androidx.compose.runtime.produceState<List<Seg>>(emptyList(), text) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { segments(Markdown.parse(text)) }
        }
        parsed
    }
    val fg = if (user) Color.White else c.text
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (seg in segs) {
            when (seg) {
                is CodeSeg -> CodeBlock(seg.block.lang, seg.block.code)
                is TextSeg -> Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
                    Box(Modifier.widthIn(max = 330.dp)
                        .clip(RoundedCornerShape(20.dp, 20.dp, if (user) 6.dp else 20.dp, if (user) 20.dp else 6.dp))
                        .background(if (user) c.accent else c.fill)) {
                        SelectionContainer {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (b in seg.blocks) BlockView(b, fg, user)
                            }
                        }
                    }
                }
            }
        }
    }
}
