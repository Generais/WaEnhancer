package com.wmods.wppenhacer.xposed.features.ponte

import android.content.SharedPreferences
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

class PonteServer(loader: ClassLoader, prefs: SharedPreferences) : Feature(loader, prefs) {

    private val PORTA = 8899

    override fun getPluginName(): String = "PonteServer"

    @Throws(Throwable::class)
    override fun doHook() {
        thread(name = "PonteServer", isDaemon = true) {
            try {
                val server = ServerSocket(PORTA)
                log("Ponte ouvindo na porta $PORTA")
                while (true) {
                    val cliente = server.accept()
                    try {
                        atende(cliente)
                    } catch (e: Throwable) {
                        log("erro ao atender: ${e.message}")
                    } finally {
                        try { cliente.close() } catch (_: Throwable) {}
                    }
                }
            } catch (e: Throwable) {
                log("Ponte falhou ao subir: ${e.message}")
            }
        }
    }

    private fun enviaComDiagnostico(numero: String, texto: String): String {
        try {
            val actionUserObj = WppCore.getActionUser()
            if (actionUserObj == null) {
                log("DIAG: getActionUser() retornou null")
                return "actionUser null"
            }
            log("DIAG: actionUser class = ${actionUserObj.javaClass.name}")

            var senderMethod: java.lang.reflect.Method? = null
            for (m in actionUserObj.javaClass.declaredMethods) {
                val retornaList = List::class.java.isAssignableFrom(m.returnType)
                val temString = m.parameterTypes.any { it == String::class.java }
                val temList = m.parameterTypes.any { it == List::class.java }
                if (retornaList && temString && temList) {
                    senderMethod = m
                    break
                }
            }
            if (senderMethod == null) {
                log("DIAG: metodo de envio (String,List)->List NAO encontrado em ${actionUserObj.javaClass.name}")
                val assinaturas = actionUserObj.javaClass.declaredMethods.take(25).joinToString("\n") {
                    "${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }}) -> ${it.returnType.simpleName}"
                }
                log("DIAG metodos disponiveis:\n$assinaturas")
                return "sender method nao encontrado"
            }
            senderMethod.isAccessible = true
            log("DIAG: sender = ${senderMethod.name}(${senderMethod.parameterTypes.joinToString { it.simpleName }})")

            var userJid = WppCore.createUserJid("$numero@s.whatsapp.net")
            if (userJid == null) {
                log("DIAG: Jid @s.whatsapp.net null, tentando @lid")
                userJid = WppCore.createUserJid("$numero@lid")
            }
            if (userJid == null) {
                log("DIAG: createUserJid retornou null nos dois formatos")
                return "userJid null"
            }
            log("DIAG: userJid class = ${userJid.javaClass.name}")

            val params = arrayOfNulls<Any>(senderMethod.parameterCount)
            for (i in params.indices) {
                params[i] = valorPadrao(senderMethod.parameterTypes[i])
            }
            val idxString = senderMethod.parameterTypes.indexOfFirst { it == String::class.java }
            val idxList = senderMethod.parameterTypes.indexOfFirst { it == List::class.java }
            params[idxString] = texto
            params[idxList] = Collections.singletonList(userJid)

            val resultado = senderMethod.invoke(actionUserObj, *params)
            log("DIAG: invoke OK, resultado = $resultado")
            return "ok"
        } catch (e: Throwable) {
            log("DIAG: EXCECAO: ${e.javaClass.simpleName}: ${e.message}")
            return "excecao: ${e.message}"
        }
    }

    private fun valorPadrao(tipo: Class<*>): Any? {
        return when (tipo) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }
    }

    private fun atende(cliente: Socket) {
        val entrada = BufferedReader(InputStreamReader(cliente.getInputStream()))
        val saida = cliente.getOutputStream()

        val requestLine = entrada.readLine() ?: return
        val partes = requestLine.split(" ")
        if (partes.size < 2) { responde(saida, 400, "{\"erro\":\"request invalido\"}"); return }
        val metodo = partes[0]
        val caminho = partes[1]

        var contentLength = 0
        var linha: String?
        while (true) {
            linha = entrada.readLine()
            if (linha == null || linha.isEmpty()) break
            if (linha.startsWith("Content-Length:", true)) {
                contentLength = linha.substringAfter(":").trim().toIntOrNull() ?: 0
            }
        }

        if (metodo == "GET" && caminho.startsWith("/status")) {
            responde(saida, 200, "{\"ok\":true}")
            return
        }

        if (metodo == "POST" && caminho.startsWith("/enviar")) {
            val corpo = CharArray(contentLength)
            var lido = 0
            while (lido < contentLength) {
                val n = entrada.read(corpo, lido, contentLength - lido)
                if (n == -1) break
                lido += n
            }
            val json = JSONObject(String(corpo, 0, lido))
            val numero = json.optString("numero").trim()
            val texto = json.optString("texto")

            if (numero.isEmpty() || texto.isEmpty()) {
                responde(saida, 400, "{\"erro\":\"numero ou texto vazio\"}")
                return
            }

            val diag = enviaComDiagnostico(numero, texto)
            log("resultado envio p/ $numero: $diag")
            val jsonResp = JSONObject().put("resultado", diag).put("numero", numero).toString()
            responde(saida, 200, jsonResp)
            return
        }

        responde(saida, 404, "{\"erro\":\"rota desconhecida\"}")
    }

    private fun responde(saida: OutputStream, status: Int, json: String) {
        val txt = when (status) {
            200 -> "200 OK"; 400 -> "400 Bad Request"
            404 -> "404 Not Found"; 500 -> "500 Server Error"
            else -> "$status"
        }
        val resp = "HTTP/1.1 $txt\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${json.toByteArray().size}\r\n" +
                "Connection: close\r\n\r\n" +
                json
        saida.write(resp.toByteArray())
        saida.flush()
    }
}
