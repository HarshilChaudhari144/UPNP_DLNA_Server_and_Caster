package com.example.mysecondapp.dlna_lib.core.services

import android.util.Log
import com.example.mysecondapp.dlna_lib.api.BrowseResult
import com.example.mysecondapp.dlna_lib.core.models.Service
import com.example.mysecondapp.dlna_lib.core.parsers.DidlLiteParser
import com.example.mysecondapp.dlna_lib.core.soap.SoapClient
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

class ContentDirectoryService(service: Service, soapClient: SoapClient) : BaseService(service, soapClient) {

    private val parser = DidlLiteParser()

    /**
     * Browses the specific container (folder) on the media server.
     */
    suspend fun browse(objectId: String, startIndex: Int, count: Int): BrowseResult {
        try {
            // 1. Send SOAP Request
            // "BrowseDirectChildren" asks for the contents of the folder.
            val rawSoapXml = action("Browse", mapOf(
                "ObjectID" to objectId,
                "BrowseFlag" to "BrowseDirectChildren",
                "Filter" to "*",
                "StartingIndex" to startIndex.toString(),
                "RequestedCount" to count.toString(),
                "SortCriteria" to ""
            ))

            // 2. Extract the inner DIDL-Lite XML from the SOAP response.
            // The actual data is an escaped string inside the <Result> tag.
            val didlXml = extractResultTag(rawSoapXml)

            if (didlXml.isEmpty()) {
                Log.w("ContentDirectoryService", "Empty Result tag in Browse response")
                return BrowseResult(emptyList(), emptyList(), 0, 0)
            }

            // 3. Parse the DIDL XML into objects
            return parser.parse(didlXml)

        } catch (e: Exception) {
            Log.e("ContentDirectoryService", "Browse failed", e)
            return BrowseResult(emptyList(), emptyList(), 0, 0)
        }
    }

    /**
     * Phase 8 Fix: Implement the abstract handleEvent from BaseService.
     * Content Directory updates (SystemUpdateID) are complex and rarely needed for simple browsing.
     * We leave this empty to satisfy the compiler.
     */
    override suspend fun handleEvent(xmlBody: String) {
        // No-op for now.
    }

    /**
     * Helper to pull the escaped XML out of the <Result> tag.
     */
    private fun extractResultTag(soapXml: String): String {
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true // Important for SOAP namespaces
            val xpp = factory.newPullParser()
            xpp.setInput(StringReader(soapXml))

            var eventType = xpp.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && xpp.name == "Result") {
                    // xpp.nextText() reads the content of the tag and automatically
                    // unescapes the XML entities (e.g., &lt; becomes <)
                    return xpp.nextText()
                }
                eventType = xpp.next()
            }
        } catch (e: Exception) {
            Log.e("ContentDirectoryService", "Failed to extract Result tag", e)
        }
        return ""
    }
}