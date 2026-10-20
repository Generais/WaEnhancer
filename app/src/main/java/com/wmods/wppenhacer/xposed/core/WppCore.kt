
    /**
     * Returns true only if a compatible method was found and invoked without throwing.
     * This is NOT delivery confirmation from WhatsApp.
     */
    @JvmStatic
    fun sendMessage(number: String, message: String): Boolean {
        try {
            val target = getActionUser() ?: run {
                YukiLog.log("Ponte: actionUser indisponivel")
                return false
            }
            val senderMethod = ReflectionUtils.findMethodUsingFilterIfExists(target.javaClass) { method ->
                List::class.java.isAssignableFrom(method.returnType) &&
                    ReflectionUtils.findIndexOfType(method.parameterTypes, String::class.java) != -1 &&
                    ReflectionUtils.findIndexOfType(method.parameterTypes, List::class.java) != -1
            } ?: run {
                YukiLog.log("Ponte: metodo de envio compativel nao encontrado")
                return false
            }
            val userJid = createUserJid("$number@s.whatsapp.net") ?: run {
                YukiLog.log("Ponte: UserJid nao encontrado")
                return false
            }
            val argumentos = arrayOfNulls<Any>(senderMethod.parameterCount)
            for (i in argumentos.indices) {
                argumentos[i] = ReflectionUtils.getDefaultValue(senderMethod.parameterTypes[i])
            }
            val indiceTexto = ReflectionUtils.findIndexOfType(senderMethod.parameterTypes, String::class.java)
            val indiceDestinatarios = ReflectionUtils.findIndexOfType(senderMethod.parameterTypes, List::class.java)
            argumentos[indiceTexto] = message
            argumentos[indiceDestinatarios] = Collections.singletonList(userJid)
            senderMethod.invoke(target, *argumentos)
            YukiLog.log("Ponte: metodo invocado (entrega nao confirmada)")
            return true
        } catch (e: Exception) {
            YukiLog.log(e)
            return false
        }
    }
