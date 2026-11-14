package io.github.zenthxsin.rustwright.pic.imaging

import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

/** 从模型回复中提取 SVG 代码，并做安全净化： 去掉脚本、事件属性与外部引用，只保留纯图形内容。 */
object SvgSanitizer {

    private val ALLOWED_ELEMENTS =
        setOf(
            "svg",
            "g",
            "defs",
            "path",
            "rect",
            "circle",
            "ellipse",
            "line",
            "polyline",
            "polygon",
            "text",
            "tspan",
            "title",
            "desc",
            "linearGradient",
            "radialGradient",
            "stop",
            "clipPath",
            "mask",
            "pattern",
            "use",
        )
    private val URL_ATTRIBUTES = setOf("href", "xlink:href")

    /** 提取回复中的第一个完整 <svg>...</svg> 片段，找不到返回 null。 */
    fun extract(reply: String): String? {
        val start = reply.indexOf("<svg")
        if (start < 0) return null
        val end = reply.lastIndexOf("</svg>")
        if (end < start) return null
        return reply.substring(start, end + "</svg>".length)
    }

    /**
     * 校验并净化 SVG 文本。输入必须是可解析的、根元素为 svg 的 XML。
     *
     * @throws IllegalArgumentException 解析失败或结构不合法，消息用于反馈给模型重试。
     */
    fun sanitize(svgText: String): String {
        require(svgText.length <= 2_000_000) { "SVG exceeds 2 MB" }
        require(
            !Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(svgText)
        ) {
            "SVG declarations and entities are not allowed"
        }
        val document = parse(svgText)
        var root = document.documentElement ?: throw IllegalArgumentException("SVG 缺少根元素")
        require(
            (root.localName ?: root.tagName) == "svg" &&
                root.namespaceURI in setOf(null, "", "http://www.w3.org/2000/svg")
        ) {
            "Root must be an SVG element in the SVG namespace"
        }
        root = normalizeNamespace(document, root, 0)
        root.setAttributeNS(
            XMLConstants.XMLNS_ATTRIBUTE_NS_URI,
            "xmlns",
            "http://www.w3.org/2000/svg",
        )
        ensureViewBox(root)
        cleanNode(root, 0)
        return serialize(document)
    }

    private fun normalizeNamespace(document: Document, element: Element, depth: Int): Element {
        require(depth <= 128) { "SVG nesting too deep" }
        require(element.namespaceURI in setOf(null, "", "http://www.w3.org/2000/svg")) {
            "Foreign SVG element namespace"
        }
        val normalized =
            if (element.namespaceURI.isNullOrEmpty())
                document.renameNode(element, "http://www.w3.org/2000/svg", element.tagName)
                    as Element
            else element
        var child = normalized.firstChild
        while (child != null) {
            val next = child.nextSibling
            if (child is Element) normalizeNamespace(document, child, depth + 1)
            child = next
        }
        return normalized
    }

    private fun parse(svgText: String): Document {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                runCatching {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }
                isExpandEntityReferences = false
            }
        return try {
            factory.newDocumentBuilder().parse(InputSource(StringReader(svgText)))
        } catch (error: Exception) {
            throw IllegalArgumentException("SVG 不是合法的 XML：${error.message}", error)
        }
    }

    private fun ensureViewBox(root: Element) {
        if (root.getAttribute("viewBox").isNotBlank()) return
        val width = root.getAttribute("width").removeSuffix("px").toFloatOrNull()
        val height = root.getAttribute("height").removeSuffix("px").toFloatOrNull()
        if (width != null && height != null && width > 0f && height > 0f) {
            root.setAttribute("viewBox", "0 0 $width $height")
        } else {
            throw IllegalArgumentException("SVG 缺少 viewBox，且 width/height 不可用")
        }
    }

    private fun cleanNode(node: Node, depth: Int) {
        require(depth <= 128) { "SVG nesting too deep" }
        var child = node.firstChild
        while (child != null) {
            val next = child.nextSibling
            if (child is Element) {
                if (localNameOf(child) !in ALLOWED_ELEMENTS) {
                    node.removeChild(child)
                } else {
                    cleanAttributes(child)
                    cleanNode(child, depth + 1)
                }
            }
            child = next
        }
        if (node is Element) cleanAttributes(node)
    }

    private fun cleanAttributes(element: Element) {
        val attributes = element.attributes
        val toRemove = mutableListOf<String>()
        for (i in 0 until attributes.length) {
            val attr = attributes.item(i)
            val name = attr.nodeName
            val lower = name.lowercase()
            when {
                lower.startsWith("on") || lower == "xml:base" || attr.nodeValue.contains('\\') ->
                    toRemove += name
                (lower in URL_ATTRIBUTES || lower.endsWith(":href")) &&
                    !attr.nodeValue.trim().startsWith("#") -> toRemove += name
                lower == "style" -> toRemove += name
                attr.nodeValue.contains("url(", ignoreCase = true) &&
                    !Regex("url\\(\\s*#[A-Za-z0-9_.:-]+\\s*\\)").matches(attr.nodeValue.trim()) ->
                    toRemove += name
            }
        }
        toRemove.forEach { element.removeAttribute(it) }
    }

    private fun localNameOf(element: Element): String =
        element.localName ?: element.tagName.substringAfterLast(':')

    private fun serialize(document: Document): String {
        val transformer =
            TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
                setOutputProperty(OutputKeys.INDENT, "no")
            }
        val writer = StringWriter()
        transformer.transform(DOMSource(document), StreamResult(writer))
        return writer.toString()
    }
}
