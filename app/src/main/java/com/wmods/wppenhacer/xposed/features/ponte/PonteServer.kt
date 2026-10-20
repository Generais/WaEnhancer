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

            try {
                WppCore.sendMessage(numero, texto)
                log("enviado para $numero")
                responde(saida, 200, "{\"ok\":true,\"numero\":\"$numero\"}")
            } catch (e: Throwable) {
                log("falha ao enviar: ${e.message}")
                responde(saida, 500, "{\"erro\":\"${e.message}\"}")
            }
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
