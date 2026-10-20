
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

class PonteServer(
    loader: ClassLoader,
    prefs: SharedPreferences
) : Feature(loader, prefs) {

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
                        try {
                            cliente.close()
                        } catch (_: Throwable) {}
                    }
                }
            } catch (e: Throwable) {
                log("Ponte falhou ao subir: ${e.message}")
            }
        }
    }

    private fun descobrir(): String {
        val sb = StringBuilder()

        try {
            val au = WppCore.getActionUser()
                ?: return "actionUser null"

            log("DESC: actionUser = ${au.javaClass.name}")
            sb.append("actionUser=${au.javaClass.name}\n")

            val classesVistas = HashSet<String>()

            fun examinar(cls: Class<*>, origem: String) {
                if (classesVistas.add(cls.name)) {
                    listarCandidatos(cls, origem, sb)
                }
            }

            examinar(au.javaClass, "actionUser")

            for (f in au.javaClass.declaredFields) {
                try {
                    f.isAccessible = true
                    val valor = f.get(au) ?: continue
                    examinar(
                        valor.javaClass,
                        "campo ${f.name}:${valor.javaClass.simpleName}"
                    )
                } catch (e: Throwable) {
                    log("DESC: erro no campo ${f.name}: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            sb.append("excecao: ${e.message}")
            log("DESC excecao: ${e.message}")
        }

        return sb.toString()
    }

    private fun listarCandidatos(
        cls: Class<*>,
        origem: String,
        sb: StringBuilder
    ) {
        try {
            val metodos = cls.declaredMethods

            log("DESC: classe=${cls.name}, metodos=${metodos.size}")

            // NOVO: mostra todas as assinaturas sem executar métodos
            for (m in metodos) {
                val assinatura =
                    "${cls.name}::${m.name}(" +
                    m.parameterTypes.joinToString { it.simpleName } +
                    ") -> ${m.returnType.simpleName}"

                log("METODO [$origem] $assinatura")
            }

            // Mantém a identificação dos candidatos
            for (m in metodos) {
                val tipos = m.parameterTypes

                val temString = tipos.any {
                    it == String::class.java ||
                    it == CharSequence::class.java
                }

                val temJid = tipos.any {
                    it.name.contains("Jid") ||
                    it == List::class.java ||
                    it == java.util.Collection::class.java
                }

                if (temString && temJid && tipos.size in 2..5) {
                    val assinatura =
                        "${cls.name}::${m.name}(" +
                        tipos.joinToString { it.simpleName } +
                        ") -> ${m.returnType.simpleName}"

                    log("CAND [$origem] $assinatura")
                    sb.append(assinatura).append("\n")
                }
            }
        } catch (e: Throwable) {
            log("DESC erro em ${cls.name}: ${e.message}")
        }
    }

    private fun atende(cliente: Socket) {
        val entrada = BufferedReader(
            InputStreamReader(cliente.getInputStream())
        )
        val saida = cliente.getOutputStream()

        val requestLine = entrada.readLine() ?: return
        val partes = requestLine.split(" ")

        if (partes.size < 2) {
            responde(saida, 400, """{"erro":"request invalido"}""")
            return
        }

        val metodo = partes[0]
        val caminho = partes[1]

        var contentLength = 0
        var linha: String?

        while (true) {
            linha = entrada.readLine()
            if (linha == null || linha.isEmpty()) break

            if (linha.startsWith("Content-Length:", true)) {
                contentLength =
                    linha.substringAfter(":").trim().toIntOrNull() ?: 0
            }
        }

        if (metodo == "GET" && caminho.startsWith("/status")) {
            responde(saida, 200, """{"ok":true}""")
            return
        }

        if (metodo == "GET" && caminho.startsWith("/descobrir")) {
            val res = descobrir()
            responde(
                saida,
                200,
                JSONObject().put("candidatos", res).toString()
            )
            return
        }

        if (metodo == "POST" && caminho.startsWith("/enviar")) {
            val corpo = CharArray(contentLength)
            var lido = 0

            while (lido < contentLength) {
                val n = entrada.read(
                    corpo,
                    lido,
                    contentLength - lido
                )

                if (n == -1) break
                lido += n
            }

            val json = JSONObject(String(corpo, 0, lido))
            val numero = json.optString("numero").trim()
            val texto = json.optString("texto")

            if (numero.isEmpty() || texto.isEmpty()) {
                responde(
                    saida,
                    400,
                    """{"erro":"numero ou texto vazio"}"""
                )
                return
            }

            responde(
                saida,
                200,
                """{"info":"use GET /descobrir primeiro"}"""
            )
            return
        }

        responde(
            saida,
            404,
            """{"erro":"rota desconhecida"}"""
        )
    }

    private fun responde(
        saida: OutputStream,
        status: Int,
        json: String
    ) {
        val txt = when (status) {
            200 -> "200 OK"
            400 -> "400 Bad Request"
            404 -> "404 Not Found"
            else -> "$status"
        }

        val bytes = json.toByteArray(Charsets.UTF_8)

        val resp = "HTTP/1.1 $txt\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"

        saida.write(resp.toByteArray(Charsets.UTF_8))
        saida.write(bytes)
        saida.flush()
    }
}
