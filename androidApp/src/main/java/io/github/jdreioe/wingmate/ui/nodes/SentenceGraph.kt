package io.github.jdreioe.wingmate.ui.nodes

import io.github.jdreioe.wingmate.domain.nodes.SentenceContent
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** Deliberately accepts only the stage-one SSML vocabulary. Never resolves external XML. */
internal fun parseSentenceSsml(source: String): List<SentenceContent>? = try {
    // Reject declarations before parsing. Android XML providers differ in supported security flags.
    require(source.length <= 20_000 && !source.contains("<!"))
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
    }
    val builder = factory.newDocumentBuilder().apply { setErrorHandler(DefaultHandler()) }
    val document = builder.parse(InputSource(StringReader(source)))
    val root = document.documentElement
    require(root.tagName == "speak" && root.attributes.length == 0)
    val contents = mutableListOf<SentenceContent>()
    for (index in 0 until root.childNodes.length) {
        val child = root.childNodes.item(index)
        when (child.nodeType) {
            Node.TEXT_NODE -> child.nodeValue.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                .forEach { contents += SentenceContent.Word(it) }
            Node.ELEMENT_NODE -> {
                require(child.nodeName == "break" && child.attributes.length == 1 && !child.hasChildNodes())
                val time = child.attributes.getNamedItem("time")?.nodeValue ?: error("time")
                val match = Regex("([0-9]+(?:\\.[0-9]+)?)(ms|s)").matchEntire(time) ?: error("time")
                val milliseconds = match.groupValues[1].toDouble() * if (match.groupValues[2] == "s") 1000 else 1
                require(milliseconds in 1.0..10000.0 && milliseconds % 1.0 == 0.0)
                contents += SentenceContent.Pause(milliseconds.toInt())
            }
            else -> error("unsupported")
        }
    }
    require(contents.size <= 100)
    contents.toList()
} catch (_: Exception) {
    null
}
