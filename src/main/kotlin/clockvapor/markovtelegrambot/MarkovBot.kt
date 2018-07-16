package clockvapor.markovtelegrambot

import com.fasterxml.jackson.databind.ObjectMapper
import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.mainBody
import me.ivmg.telegram.Bot
import me.ivmg.telegram.bot
import me.ivmg.telegram.dispatch
import me.ivmg.telegram.dispatcher.handlers.Handler
import me.ivmg.telegram.entities.Message
import me.ivmg.telegram.entities.MessageEntity
import me.ivmg.telegram.entities.Update
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.nio.file.Paths
import java.util.*

class MarkovBot(val name: String, val token: String, val dataPath: String) {
    private var myId: Long? = null
    private val wantToDeleteOwnData = mutableMapOf<String, MutableSet<String>>()
    private val wantToDeleteUserData = mutableMapOf<String, MutableMap<String, String>>()
    private val wantToDeleteMessageData = mutableMapOf<String, MutableMap<String, String>>()

    fun run() {
        val bot = bot {
            this@bot.token = this@MarkovBot.token
            logLevel = HttpLoggingInterceptor.Level.NONE
            dispatch {
                addHandler(object : Handler({ bot, update -> handleUpdate(bot, update) }) {
                    override val groupIdentifier = "None"
                    override fun checkUpdate(update: Update) = update.message != null
                })
            }
        }
        myId = bot.getMe().first?.body()?.result?.id ?: throw Exception("Failed to retrieve bot's user ID")
        log("Bot ID = $myId")
        log("Bot started")
        bot.startPolling()
    }

    private fun handleUpdate(bot: Bot, update: Update) {
        update.message?.let {
            handleMessage(bot, it)
        }
    }

    private fun handleMessage(bot: Bot, message: Message) {
        val chatId = message.chat.id.toString()
        message.newChatMember?.let { newUser ->
            if (newUser.id == myId!!) {
                log("Added to group $chatId")
            }
        }
        message.leftChatMember?.let { leftUser ->
            if (leftUser.id == myId!!) {
                log("Removed from group $chatId")
                deleteChat(chatId)
            }
        }
        message.from?.let { from ->
            val senderId = from.id.toString()
            from.username?.let { username ->
                if (username.isNotEmpty()) {
                    storeUsername(username, senderId)
                }
            }

            message.text?.let { text ->
                var shouldAnalyze = true
                val deleteOwnData = wantToDeleteOwnData[chatId]
                val deleteUserData = wantToDeleteUserData[chatId]
                val deleteMessageData = wantToDeleteMessageData[chatId]

                if (deleteOwnData?.contains(senderId) == true) {
                    shouldAnalyze = false
                    deleteOwnData -= senderId
                    if (deleteOwnData.isEmpty()) {
                        wantToDeleteOwnData -= chatId
                    }
                    val replyText = if (text.trim().toLowerCase(Locale.ENGLISH) == "yes") {
                        if (deleteMarkov(chatId, senderId)) {
                            "Okay. I deleted your Markov chain data in this group."
                        } else {
                            "Hmm. I tried to delete your Markov chain data in this group, but something went wrong."
                        }
                    } else {
                        "Okay. I won't delete your Markov chain data in this group then."
                    }
                    reply(bot, message, replyText)

                } else if (deleteUserData?.contains(senderId) == true) {
                    shouldAnalyze = false
                    val userIdToDelete = deleteUserData[senderId]!!
                    deleteUserData -= senderId
                    if (deleteUserData.isEmpty()) {
                        wantToDeleteUserData -= chatId
                    }
                    val replyText = if (text.trim().toLowerCase(Locale.ENGLISH) == "yes") {
                        if (deleteMarkov(chatId, userIdToDelete)) {
                            "Okay. I deleted their Markov chain data in this group."
                        } else {
                            "Hmm. I tried to delete their Markov chain data in this group, but something went wrong."
                        }
                    } else {
                        "Okay. I won't delete their Markov chain data in this group then."
                    }
                    reply(bot, message, replyText)

                } else if (deleteMessageData?.contains(senderId) == true) {
                    shouldAnalyze = false
                    val messageToDelete = deleteMessageData[senderId]!!
                    deleteMessageData -= senderId
                    if (deleteMessageData.isEmpty()) {
                        wantToDeleteMessageData -= chatId
                    }
                    val replyText = if (text.trim().toLowerCase(Locale.ENGLISH) == "yes") {
                        deleteMessage(chatId, senderId, messageToDelete)
                        "Okay. I deleted that message from your Markov chain data in this group."
                    } else {
                        "Okay. I won't delete that message from your Markov chain data in this group then."
                    }
                    reply(bot, message, replyText)
                }


                message.entities?.let { entities ->
                    if (entities.isNotEmpty()) {
                        val e0 = entities[0]
                        if (e0.type == "bot_command" && e0.offset == 0) {
                            shouldAnalyze = false
                            val e0Text = getMessageEntityText(message, e0)

                            if (matchesCommand(e0Text, "msg")) {
                                val replyText = if (entities.size < 2) {
                                    null
                                } else {
                                    val e1 = entities[1]
                                    if (e1.isMention()) {
                                        val (mentionUserId, e1Text) = getMentionUserId(message, e1)
                                        if (mentionUserId == null) {
                                            null
                                        } else {
                                            generateMessage(chatId, mentionUserId)
                                        } ?: "<no data available for $e1Text>"
                                    } else {
                                        null
                                    }
                                } ?: "<expected a user mention>"
                                reply(bot, message, replyText)

                            } else if (matchesCommand(e0Text, "deletemydata")) {
                                wantToDeleteOwnData.getOrPut(chatId) { mutableSetOf() } += senderId
                                val replyText =
                                    "Are you sure you want to delete your Markov chain data in this group? " +
                                        "Say \"yes\" to confirm, or anything else to cancel."
                                reply(bot, message, replyText)

                            } else if (matchesCommand(e0Text, "deleteuserdata")) {
                                val replyText = if (isAdmin(bot, message.chat, from.id)) {
                                    if (entities.size < 2) {
                                        null
                                    } else {
                                        val e1 = entities[1]
                                        if (e1.isMention()) {
                                            val (mentionUserId, e1Text) = getMentionUserId(message, e1)
                                            if (mentionUserId == null) {
                                                null
                                            } else {
                                                wantToDeleteUserData
                                                    .getOrPut(chatId) { mutableMapOf() }[senderId] = mentionUserId
                                                "Are you sure you want to delete $e1Text's Markov chain data in " +
                                                    "this group? Say \"yes\" to confirm, or anything else to cancel."
                                            } ?: "I don't have any data for $e1Text."
                                        } else {
                                            null
                                        }
                                    } ?: "You need to tell me which user's data to delete."
                                } else {
                                    "You aren't an administrator."
                                }
                                reply(bot, message, replyText)

                            } else if (matchesCommand(e0Text, "deletemessagedata")) {
                                val replyText = message.replyToMessage?.let { replyToMessage ->
                                    replyToMessage.from?.let { replyToMessageFrom ->
                                        if (senderId == replyToMessageFrom.id.toString()) {
                                            wantToDeleteMessageData.getOrPut(chatId) { mutableMapOf() }[senderId] =
                                                replyToMessage.text ?: ""
                                            "Are you sure you want to delete that message from your Markov chain " +
                                                "data in this group? Say \"yes\" to confirm, or anything else to " +
                                                "cancel."
                                        } else {
                                            null
                                        }
                                    } ?: "That isn't your message."
                                } ?: "You need to reply to your message whose data you want to delete."
                                reply(bot, message, replyText)
                            }
                        }
                    }
                }
                if (shouldAnalyze) {
                    analyzeMessage(message)
                }
            }
        }
    }

    private fun analyzeMessage(message: Message) {
        val path = getMarkovPath(message.chat.id.toString(), message.from!!.id.toString())
        val markovChain = tryOrNull { MarkovChain.read(path) } ?: MarkovChain()
        markovChain.add(message.text!!.split(whitespaceRegex))
        markovChain.write(path)
    }

    private fun generateMessage(chatId: String, userId: String): String? =
        tryOrNull { MarkovChain.read(getMarkovPath(chatId, userId)) }?.generate()?.joinToString(" ")

    private fun reply(bot: Bot, message: Message, text: String) {
        bot.sendMessage(message.chat.id, text, replyToMessageId = message.messageId.toInt())
    }

    private fun getMentionUserId(message: Message, entity: MessageEntity): Pair<String?, String> {
        val text = getMessageEntityText(message, entity)
        val id = when (entity.type) {
            "mention" -> getUserIdForUsername(text.drop(1))
            "text_mention" -> entity.user?.id?.toString()
            else -> null
        }
        return Pair(id, text)
    }

    private fun getUserIdForUsername(username: String): String? =
        readUsernames()?.get(username.toLowerCase(Locale.ENGLISH))

    private fun storeUsername(username: String, userId: String) {
        val usernames = readUsernames() ?: mutableMapOf()
        usernames[username.toLowerCase(Locale.ENGLISH)] = userId
        writeUsernames(usernames)
    }

    @Suppress("UNCHECKED_CAST")
    private fun readUsernames(): MutableMap<String, String>? = tryOrNull {
        ObjectMapper().readValue<MutableMap<*, *>>(File(getUsernamesPath()),
            MutableMap::class.java) as MutableMap<String, String>
    }

    private fun writeUsernames(usernames: Map<String, String>) {
        ObjectMapper().writeValue(File(getUsernamesPath()), usernames)
    }

    private fun deleteChat(chatId: String): Boolean =
        File(getChatPath(chatId)).deleteRecursively()

    private fun deleteMarkov(chatId: String, userId: String): Boolean =
        File(getMarkovPath(chatId, userId)).delete()

    private fun deleteMessage(chatId: String, userId: String, text: String) {
        val path = getMarkovPath(chatId, userId)
        tryOrNull { MarkovChain.read(path) }?.let { markovChain ->
            markovChain.remove(text.split(whitespaceRegex))
            markovChain.write(path)
        }
    }

    private fun getMarkovPath(chatId: String, userId: String): String =
        Paths.get(getChatPath(chatId), "$userId.json").toString()

    private fun getChatPath(chatId: String): String {
        val path = Paths.get(dataPath, chatId).toString()
        File(path).mkdirs()
        return path
    }

    private fun getUsernamesPath(): String {
        File(dataPath).mkdirs()
        return Paths.get(dataPath, "usernames.json").toString()
    }

    private fun matchesCommand(text: String, command: String): Boolean =
        text == "/$command" || text == "/$command@$name"

    companion object {
        private val whitespaceRegex = Regex("\\s+")

        @JvmStatic
        fun main(args: Array<String>) = mainBody {
            val a = ArgParser(args).parseInto(::Args)
            val config = Config.read(a.configPath)
            MarkovBot(config.telegramBotName, config.telegramBotToken, a.dataPath).run()
        }
    }

    class Args(parser: ArgParser) {
        val configPath by parser.storing("-c", "--config", help = "Path to config YAML file")
        val dataPath by parser.storing("-d", "--data", help = "Path to data directory")
    }
}
