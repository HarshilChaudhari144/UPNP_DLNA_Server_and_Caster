package com.example.mysecondapp.dlna_lib.core.scpd

import com.example.mysecondapp.dlna_lib.core.logging.DlnaLogger
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

internal class ScpdParser {

    private val tag = "ScpdParser"

    /**
     * Parses the SCPD XML and returns a Set of supported Action names.
     */
    fun parseActions(xml: String): Set<String> {
        val actions = mutableSetOf<String>()
        if (xml.isBlank()) return actions

        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true // SCPD uses namespaces heavily
            val builder = factory.newDocumentBuilder()
            val inputSource = InputSource(StringReader(xml))
            val doc = builder.parse(inputSource)

            doc.documentElement.normalize()

            // <actionList> -> <action> -> <name>
            val actionLists = doc.getElementsByTagNameNS("*", "actionList")
            if (actionLists.length > 0) {
                val actionList = actionLists.item(0) as Element
                val actionNodes = actionList.getElementsByTagNameNS("*", "action")

                for (i in 0 until actionNodes.length) {
                    val actionNode = actionNodes.item(i)
                    if (actionNode.nodeType == Node.ELEMENT_NODE) {
                        val element = actionNode as Element
                        val name = getTagValue(element, "name")
                        if (!name.isNullOrBlank()) {
                            actions.add(name)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            DlnaLogger.w(tag, "Failed to parse SCPD: ${e.message}")
        }

        return actions
    }

    private fun getTagValue(element: Element, tagName: String): String? {
        val list = element.getElementsByTagNameNS("*", tagName)
        if (list.length > 0) return list.item(0).textContent?.trim()
        return null
    }
}