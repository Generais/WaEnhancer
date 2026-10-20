
package com.wmods.wppenhacer.xposed.features.ponte

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PonteServer(loader: ClassLoader, prefs: SharedPreferences) : Feature(loader, prefs) {
    private val porta = 8899

    override fun getPluginName(): String = "PonteServer"

    override fun doHook() {
        thread(name = "PonteServer", isDaemon = true) {
            try {
                ServerSocket(porta, 8, InetAddress.getLoopbackAddress()).use { server ->
                    log("Ponte ouvindo apenas no loopback na porta $porta")
                    while (true) {
                        val cliente = server.accept()
                        try {
                            cliente.soTimeout = 15000
                            atende(cliente)
                        } catch (e: Exception) {
                            log("Erro no atendimento: ${e.javaClass.simpleName}: ${e.message}")
                        } finally {
                            try { cliente.close() } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                log("Ponte falhou ao subir: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun lerCabecalho(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val fim = byteArrayOf(13, 10, 13, 10)
        while (bytes.size() < 8192) {
            val atual = input.read()
            if (atual == -1) throw IllegalStateException("cabecalho incompleto")
            bytes.write(atual)
            val conteudo = bytes.toByteArray()
            if (conteudo.size >= 4 && conteudo.takeLast(4).toByteArray().contentEquals(fim)) {
                return String(conteudo, Charsets.ISO_8859_1)
            }
        }
        throw IllegalArgumentException("cabecalho muito grande")
    }

    private fun atende(cliente: Socket) {
        val entrada = cliente.getInputStream()
        val saida = cliente.getOutputStream()
        val linhas = lerCabecalho(entrada).split("\r\n")
        val partes = linhas[0].split(' ')
        if (partes.size < 2) return responde(saida, 400, "request invalido")
        val metodo = partes[0]
        val caminho = partes[1].substringBefore('?')
        val tamanho = linhas.firstOrNull { it.startsWith("Content-Length:", true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: -1

        if (metodo == "GET" && caminho == "/status") {
            return responde(saida, 200, "ok", JSONObject().put("ok", true))
        }
        if (metodo == "GET" && caminho == "/descobrir") {
            val obj = WppCore.getActionUser()
            val info = JSONObject().put("actionUser", obj?.javaClass?.name ?: "null")
            return responde(saida, 200, "diagnostico", info)
        }
        if (metodo == "POST" && caminho == "/enviar") {
            if (tamanho < 1 || tamanho > 16384) return responde(saida, 400, "tamanho invalido")
            val dados = ByteArray(tamanho)
            var lidos = 0
            while (lidos < dados.size) {
                val n = entrada.read(dados, lidos, dados.size - lidos)
                if (n == -1) return responde(saida, 400, "corpo incompleto")
                lidos += n
            }
            val json = try {
                JSONObject(String(dados, Charsets.UTF_8))
            } catch (_: Exception) {
                return responde(saida, 400, "json invalido")
            }
            val numero = json.optString("numero").trim()
            val texto = json.optString("texto")
            if (!numero.matches(Regex("[0-9]{8,15}")) || texto.isBlank() || texto.length > 4000) {
                return responde(saida, 400, "numero ou texto invalido")
            }
            val trava = CountDownLatch(1)
            var invocado = false
            Handler(Looper.getMainLooper()).post {
                try {
                    invocado = WppCore.sendMessage(numero, texto)
                } catch (e: Exception) {
                    log("Erro no envio: ${e.message}")
                } finally {
                    trava.countDown()
                }
            }
            if (!trava.await(10, TimeUnit.SECONDS)) return responde(saida, 503, "tempo esgotado")
            return if (invocado) {
                responde(saida, 200, "metodo invocado; entrega nao confirmada", JSONObject().put("invocado", true))
            } else {
                responde(saida, 503, "metodo de envio incompativel ou indisponivel", JSONObject().put("invocado", false))
            }
        }
        responde(saida, 404, "rota desconhecida")
    }

    private fun responde(saida: OutputStream, status: Int, mensagem: String, extra: JSONObject? = null) {
        val objeto = extra ?: JSONObject()
        objeto.put("mensagem", mensagem)
        val conteudo = objeto.toString().toByteArray(Charsets.UTF_8)
        val motivo = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val cabecalho = "HTTP/1.1 $status $motivo\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${conteudo.size}\r\nConnection: close\r\n\r\n"
        saida.write(cabecalho.toByteArray(Charsets.UTF_8))
        saida.write(conteudo)
        saida.flush()
    }
}
