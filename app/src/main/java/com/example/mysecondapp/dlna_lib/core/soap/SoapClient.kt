package com.example.mysecondapp.dlna_lib.core.soap

import com.example.mysecondapp.dlna_lib.api.errors.DlnaError
import com.example.mysecondapp.dlna_lib.core.error.PublicErrorMapper
import com.example.mysecondapp.dlna_lib.core.logging.DlnaLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

internal class SoapClient {

    private val tag = "SoapClient"

    /**
     * Sends a SOAP Action to a DLNA device.
     */
    suspend fun sendAction(
        controlUrl: String,
        serviceType: String,
        actionName: String,
        arguments: Map<String, String>
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                performRequest(controlUrl, serviceType, actionName, arguments)
            } catch (t: Throwable) {
                // Map raw IO/Network errors to our library taxonomy
                throw PublicErrorMapper.map(t)
            }
        }
    }

    private fun performRequest(
        urlStr: String,
        serviceType: String,
        actionName: String,
        args: Map<String, String>
    ): String {
        val soapBody = buildSoapBody(serviceType, actionName, args)
        val soapActionHeader = "\"$serviceType#$actionName\""

        DlnaLogger.d(tag, "Sending $actionName to $urlStr")

        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        conn.setRequestProperty("SOAPAction", soapActionHeader)
        conn.setRequestProperty("Connection", "Close")

        conn.outputStream.use { os ->
            OutputStreamWriter(os, "UTF-8").use { writer ->
                writer.write(soapBody)
                writer.flush()
            }
        }

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            // Read error stream for debugging
            val errorMsg = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            DlnaLogger.w(tag, "SOAP Fail ($responseCode): $errorMsg")
            throw DlnaError.Network("SOAP Action failed: HTTP $responseCode")
        }

        return conn.inputStream.bufferedReader().use(BufferedReader::readText)
    }

    private fun buildSoapBody(serviceType: String, action: String, args: Map<String, String>): String {
        val argsXml = args.entries.joinToString("") { "<${it.key}>${it.value}</${it.key}>" }
        return """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:$action xmlns:u="$serviceType">
                        $argsXml
                    </u:$action>
                </s:Body>
            </s:Envelope>
        """.trimIndent()
    }
}