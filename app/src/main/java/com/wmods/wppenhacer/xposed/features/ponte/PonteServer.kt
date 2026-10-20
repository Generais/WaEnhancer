
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

class PonteServer(
    loader: ClassLoader,
    prefs: SharedPreferences
) : Feature(loader, prefs) {

    private val porta = 8899

    override fun getPluginName(): String = "PonteServer"

    override fun doHook() {
        thread(name = "PonteServer", isDaemon = true) {
            try {
                ServerSocket(
                    porta,
                    8,
                    InetAddress.getLoopbackAddress()
                ).use { server ->

                    log("Ponte ouvindo na porta $porta")

                    while (true) {
                        val cliente = server.accept()

                        try {
                            cliente.soTimeout = 15000
                            atende(cliente)
                        } catch (e: Exception) {
                            log(
                                "Erro no atendimento: " +
                                "${e.javaClass.simpleName}: ${e.message}"
                            )
                        } finally {
                            try {
                                cliente.close()
                            } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                log(
                    "Ponte falhou ao subir: " +
                    "${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
    }

    private fun lerCabecalho(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val fim = byteArrayOf(13, 10, 13, 10)

        while (bytes.size() < 8192) {
            val atual = input.read()

            if (atual == -1) {
                throw IllegalStateException("cabecalho incompleto")
            }

            bytes.write(atual)
            val conteudo = bytes.toByteArray()

            if (
                conteudo.size >= 4 &&
                conteudo.takeLast(4)
                    .toByteArray()
                    .contentEquals(fim)
            ) {
                return String(
                    conteudo,
                    Charsets.ISO_8859_1
                )
            }
        }

        throw IllegalArgumentException("cabecalho muito grande")
    }

    private fun atende(cliente: Socket) {
        val entrada = cliente.getInputStream()
        val saida = cliente.getOutputStream()

        val linhas = lerCabecalho(entrada).split("\r\n")
        val partes = linhas[0].split(' ')

        if (partes.size < 2) {
            responde(saida, 400, "request invalido")
            return
        }

        val metodo = partes[0]
        val caminho = partes[1].substringBefore('?')

        val tamanho = linhas
            .firstOrNull {
                it.startsWith("Content-Length:", true)
            }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull() ?: -1

        // STATUS
        if (metodo == "GET" && caminho == "/status") {
            responde(
                saida,
                200,
                "ok",
                JSONObject().put("ok", true)
            )
            return
        }

        // DIAGNOSTICO
        if (metodo == "GET" && caminho == "/descobrir") {
            val obj = WppCore.getActionUser()

            val info = JSONObject().put(
                "actionUser",
                obj?.javaClass?.name ?: "null"
            )

            responde(
                saida,
                200,
                "diagnostico",
                info
            )
            return
        }

        // ENVIO DE MENSAGENS
        if (metodo == "POST" && caminho == "/enviar") {

            if (tamanho < 1 || tamanho > 16384) {
                responde(saida, 400, "tamanho invalido")
                return
            }

            val dados = ByteArray(tamanho)
            var lidos = 0

            while (lidos < dados.size) {
                val n = entrada.read(
                    dados,
                    lidos,
                    dados.size - lidos
                )

                if (n == -1) {
                    responde(saida, 400, "corpo incompleto")
                    return
                }

                lidos += n
            }

            val json = try {
                JSONObject(String(dados, Charsets.UTF_8))
            } catch (_: Exception) {
                responde(saida, 400, "json invalido")
                return
            }

            val numero = json.optString("numero").trim()
            val texto = json.optString("texto")

            if (
                !numero.matches(Regex("[0-9]{8,15}")) ||
                texto.isBlank() ||
                texto.length > 4000
            ) {
                responde(saida, 400, "numero ou texto invalido")
                return
            }

            val trava = CountDownLatch(1)

            var erro: String? = null

            val agendado = Handler(
                Looper.getMainLooper()
            ).post {
                try {
                    // WppCore original retorna Unit, nao Boolean
                    WppCore.sendMessage(numero, texto)

                    log(
                        "Ponte: chamada de envio executada " +
                        "(entrega nao confirmada)"
                    )

                } catch (e: Exception) {
                    erro = e.message ?: e.javaClass.simpleName
                    log("Ponte: erro no envio: $erro")
                } finally {
                    trava.countDown()
                }
            }

            if (!agendado) {
                responde(
                    saida,
                    503,
                    "nao foi possivel agendar o envio"
                )
                return
            }

            if (!trava.await(10, TimeUnit.SECONDS)) {
                responde(
                    saida,
                    503,
                    "tempo esgotado; estado do envio desconhecido"
                )
                return
            }

            if (erro != null) {
                responde(
                    saida,
                    503,
                    "erro ao chamar funcao de envio",
                    JSONObject().put("erro", erro)
                )
                return
            }

            responde(
                saida,
                200,
                "funcao chamada; envio e entrega nao confirmados",
                JSONObject().put("chamadaExecutada", true)
            )
            return
        }

        responde(
            saida,
            404,
            "rota desconhecida"
        )
    }

    private fun responde(
        saida: OutputStream,
        status: Int,
        mensagem: String,
        extra: JSONObject? = null
    ) {
        val objeto = extra ?: JSONObject()
        objeto.put("mensagem", mensagem)

        val conteudo = objeto.toString()
            .toByteArray(Charsets.UTF_8)

        val motivo = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            503 -> "Service Unavailable"
            else -> "Error"
        }

        val cabecalho =
            "HTTP/1.1 $status $motivo\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${conteudo.size}\r\n" +
            "Connection: close\r\n\r\n"

        saida.write(cabecalho.toByteArray(Charsets.UTF_8))
        saida.write(conteudo)
        saida.flush()
    }
}
