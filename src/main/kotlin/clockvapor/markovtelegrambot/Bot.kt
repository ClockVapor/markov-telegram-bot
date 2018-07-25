package clockvapor.markovtelegrambot

import clockvapor.markov.MarkovChain
import com.fasterxml.jackson.databind.ObjectMapper
import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.mainBody
import me.ivmg.telegram.Bot
import me.ivmg.telegram.bot
import me.ivmg.telegram.dispatch
import me.ivmg.telegram.dispatcher.handlers.Handler
import me.ivmg.telegram.entities.*
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.nio.file.Paths
import java.util.*

class Bot(val token: String, val dataPath: String) {
    private var myId: Long? = null
    private lateinit var myUsername: String
    private val wantToDeleteOwnData = mutableMapOf<String, MutableSet<String>>()
    private val wantToDeleteUserData = mutableMapOf<String, MutableMap<String, String>>()
    private val wantToDeleteMessageData = mutableMapOf<String, MutableMap<String, String>>()

    fun run() {
        val bot = bot {
            this@bot.token = this@Bot.token
            logLevel = HttpLoggingInterceptor.Level.NONE
            dispatch {
                addHandler(object : Handler({ bot, update -> handleUpdate(bot, update) }) {
                    override val groupIdentifier = "None"
                    override fun checkUpdate(update: Update) = update.message != null
                })
            }
        }
        bot.getMe().first?.body()?.result?.let { me ->
            myId = me.id
            myUsername = me.username ?: throw Exception("Bot has no username")
        } ?: throw Exception("Failed to retrieve bot's user")
        log("Bot ID = $myId")
        log("Bot username = $myUsername")
        log("Bot started")
        bot.startPolling()
    }

    private fun handleUpdate(bot: Bot, update: Update) {
        update.message?.let { tryOrLog { handleMessage(bot, it) } }
    }

    private fun handleMessage(bot: Bot, message: Message) {
        val chatId = message.chat.id.toString()
        message.newChatMember?.takeIf { it.id == myId!! }?.let { log("Added to group $chatId") }
        message.leftChatMember?.takeIf { it.id == myId!! }?.let {
            log("Removed from group $chatId")
            tryOrLog { deleteChat(chatId) }
        }
        message.from?.let { handleMessage(bot, message, chatId, it) }
    }

    private fun handleMessage(bot: Bot, message: Message, chatId: String, from: User) {
        val senderId = from.id.toString()
        from.username?.let { tryOrLog { storeUsername(it, senderId) } }
        val text = message.text
        val caption = message.caption
        if (text != null) {
            handleMessage(bot, message, chatId, from, senderId, text)
        } else if (caption != null) {
            handleMessage(bot, message, chatId, from, senderId, caption)
        }
    }

    private fun handleMessage(bot: Bot, message: Message, chatId: String, from: User, senderId: String, text: String) {
        var shouldAnalyzeMessage = handleQuestionResponse(bot, message, chatId, senderId, text)
        if (shouldAnalyzeMessage) {
            message.entities?.takeIf { it.isNotEmpty() }?.let {
                shouldAnalyzeMessage = handleMessage(bot, message, chatId, from, senderId, text, it)
            }
        }
        if (shouldAnalyzeMessage) {
            analyzeMessage(chatId, senderId, text)
        }
    }

    private fun handleMessage(bot: Bot, message: Message, chatId: String, from: User, senderId: String, text: String,
                              entities: List<MessageEntity>): Boolean {

        var shouldAnalyzeMessage = true
        val e0 = entities[0]
        if (e0.type == "bot_command" && e0.offset == 0) {
            shouldAnalyzeMessage = false
            val e0Text = getMessageEntityText(message, e0)

            when {
                matchesCommand(e0Text, "msg") ->
                    doMessageCommand(bot, message, chatId, text, entities)

                matchesCommand(e0Text, "deletemydata") ->
                    doDeleteMyDataCommand(bot, message, chatId, senderId)

                matchesCommand(e0Text, "deleteuserdata") ->
                    doDeleteUserDataCommand(bot, message, chatId, from, senderId, entities)

                matchesCommand(e0Text, "deletemessagedata") ->
                    doDeleteMessageDataCommand(bot, message, chatId, senderId)
            }
        }

        return shouldAnalyzeMessage
    }

    private fun handleQuestionResponse(bot: Bot, message: Message, chatId: String, senderId: String,
                                       text: String): Boolean {

        var shouldAnalyzeMessage = true
        val deleteOwnData = wantToDeleteOwnData[chatId]
        val deleteUserData = wantToDeleteUserData[chatId]
        val deleteMessageData = wantToDeleteMessageData[chatId]

        if (deleteOwnData?.contains(senderId) == true) {
            shouldAnalyzeMessage = false
            deleteOwnData -= senderId
            if (deleteOwnData.isEmpty()) {
                wantToDeleteOwnData -= chatId
            }
            val replyText = if (text.trim().toLowerCase(Locale.ENGLISH) == YES) {
                if (tryOrDefault(false) { deleteMarkov(chatId, senderId) }) {
                    "Okay. I deleted your Markov chain data in this group."
                } else {
                    "Hmm. I tried to delete your Markov chain data in this group, but something went wrong."
                }
            } else {
                "Okay. I won't delete your Markov chain data in this group then."
            }
            reply(bot, message, replyText)

        } else if (deleteUserData?.contains(senderId) == true) {
            shouldAnalyzeMessage = false
            val userIdToDelete = deleteUserData[senderId]!!
            deleteUserData -= senderId
            if (deleteUserData.isEmpty()) {
                wantToDeleteUserData -= chatId
            }
            val replyText = if (text.trim().toLowerCase(Locale.ENGLISH) == YES) {
                if (tryOrDefault(false) { deleteMarkov(chatId, userIdToDelete) }) {
                    "Okay. I deleted their Markov chain data in this group."
                } else {
                    "Hmm. I tried to delete their Markov chain data in this group, but something went wrong."
                }
            } else {
                "Okay. I won't delete their Markov chain data in this group then."
            }
            reply(bot, message, replyText)

        } else if (deleteMessageData?.contains(senderId) == true) {
            shouldAnalyzeMessage = false
            val messageToDelete = deleteMessageData[senderId]!!
            deleteMessageData -= senderId
            if (deleteMessageData.isEmpty()) {
                wantToDeleteMessageData -= chatId
            }
            val replyText = if (text.trim().toLowerCase(Locale.ENGLISH) == YES) {
                if (trySuccessful { deleteMessage(chatId, senderId, messageToDelete) })
                    "Okay. I deleted that message from your Markov chain data in this group."
                else
                    "Hmm. I tried to delete that message from your Markov chain data in this group, but something " +
                        "went wrong."
            } else {
                "Okay. I won't delete that message from your Markov chain data in this group then."
            }
            reply(bot, message, replyText)
        }

        return shouldAnalyzeMessage
    }

    private fun doMessageCommand(bot: Bot, message: Message, chatId: String, text: String,
                                 entities: List<MessageEntity>) {

        var parseMode: ParseMode? = null

        val replyText = if (entities.size < 2) {
            null
        } else {
            val e1 = entities[1]
            if (e1.isMention()) {
                val (mentionUserId, e1Text) = getMentionUserId(message, e1)
                val formattedUsername = if (mentionUserId != null && e1.type == "text_mention") {
                    parseMode = ParseMode.MARKDOWN
                    createInlineMention(e1Text, mentionUserId)
                } else {
                    e1Text
                }
                if (mentionUserId == null) {
                    null
                } else {
                    val remainingTexts = text.substring(e1.offset + e1.length).trim().takeIf { it.isNotBlank() }
                        ?.split(whitespaceRegex).orEmpty()
                    when (remainingTexts.size) {
                        0 -> generateMessage(chatId, mentionUserId)

                        1 -> generateMessage(chatId, mentionUserId, remainingTexts.first())?.let { result ->
                            when (result) {
                                is MarkovChain.GenerateWithSeedResult.NoSuchSeed ->
                                    "<no such seed exists for $formattedUsername>"
                                is MarkovChain.GenerateWithSeedResult.Success ->
                                    result.message.takeIf { it.isNotEmpty() }?.joinToString(" ")
                            }
                        }

                        else -> "<expected only one seed word>"
                    }
                } ?: "<no data available for $formattedUsername>"
            } else {
                null
            }
        } ?: "<expected a user mention>"
        reply(bot, message, replyText, parseMode)
    }

    private fun doDeleteMyDataCommand(bot: Bot, message: Message, chatId: String, senderId: String) {
        wantToDeleteOwnData.getOrPut(chatId) { mutableSetOf() } += senderId
        val replyText = "Are you sure you want to delete your Markov chain data in this group? " +
            "Say \"yes\" to confirm, or anything else to cancel."
        reply(bot, message, replyText)
    }

    private fun doDeleteUserDataCommand(bot: Bot, message: Message, chatId: String, from: User, senderId: String,
                                        entities: List<MessageEntity>) {

        var parseMode: ParseMode? = null
        val replyText = if (isAdmin(bot, message.chat, from.id)) {
            if (entities.size < 2) {
                null
            } else {
                val e1 = entities[1]
                if (e1.isMention()) {
                    val (mentionUserId, e1Text) = getMentionUserId(message, e1)
                    val formattedUsername = if (mentionUserId != null && e1.type == "text_mention") {
                        parseMode = ParseMode.MARKDOWN
                        createInlineMention(e1Text, mentionUserId)
                    } else {
                        e1Text
                    }
                    if (mentionUserId == null) {
                        null
                    } else {
                        wantToDeleteUserData.getOrPut(chatId) { mutableMapOf() }[senderId] = mentionUserId
                        "Are you sure you want to delete $formattedUsername's Markov chain data in " +
                            "this group? Say \"yes\" to confirm, or anything else to cancel."
                    } ?: "I don't have any data for $formattedUsername."
                } else {
                    null
                }
            } ?: "You need to tell me which user's data to delete."
        } else {
            "You aren't an administrator."
        }
        reply(bot, message, replyText, parseMode)
    }

    private fun doDeleteMessageDataCommand(bot: Bot, message: Message, chatId: String, senderId: String) {
        val replyText = message.replyToMessage?.let { replyToMessage ->
            replyToMessage.from?.takeIf { it.id.toString() == senderId }?.let { replyToMessageFrom ->
                wantToDeleteMessageData.getOrPut(chatId) { mutableMapOf() }[senderId] = replyToMessage.text ?: ""
                "Are you sure you want to delete that message from your Markov chain " +
                    "data in this group? Say \"yes\" to confirm, or anything else to cancel."
            } ?: "That isn't your message."
        } ?: "You need to reply to your message whose data you want to delete."
        reply(bot, message, replyText)
    }

    private fun analyzeMessage(chatId: String, userId: String, text: String) {
        val path = getMarkovPath(chatId, userId)
        val markovChain = tryOrNull { MarkovChain.read(path) } ?: MarkovChain()
        markovChain.add(text.split(whitespaceRegex))
        markovChain.write(path)
    }

    private fun generateMessage(chatId: String, userId: String): String? =
        tryOrNull { MarkovChain.read(getMarkovPath(chatId, userId)) }?.generate()
            ?.takeIf { it.isNotEmpty() }?.joinToString(" ")

    private fun generateMessage(chatId: String, userId: String,
                                seed: String): MarkovChain.GenerateWithSeedResult? =
        tryOrNull { MarkovChain.read(getMarkovPath(chatId, userId)) }?.generateWithCaseInsensitiveSeed(seed)

    private fun reply(bot: Bot, message: Message, text: String, parseMode: ParseMode? = null) {
        bot.sendMessage(message.chat.id, text, replyToMessageId = message.messageId.toInt(), parseMode = parseMode)
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
        tryOrNull { readUsernames() }?.get(username.toLowerCase(Locale.ENGLISH))

    private fun storeUsername(username: String, userId: String) {
        val usernames = tryOrNull { readUsernames() } ?: mutableMapOf()
        usernames[username.toLowerCase(Locale.ENGLISH)] = userId
        writeUsernames(usernames)
    }

    @Suppress("UNCHECKED_CAST")
    private fun readUsernames(): MutableMap<String, String> =
        ObjectMapper().readValue<MutableMap<*, *>>(File(getUsernamesPath()), MutableMap::class.java)
            as MutableMap<String, String>

    private fun writeUsernames(usernames: Map<String, String>) {
        ObjectMapper().writeValue(File(getUsernamesPath()), usernames)
    }

    private fun deleteChat(chatId: String): Boolean =
        File(getChatPath(chatId)).deleteRecursively()

    private fun deleteMarkov(chatId: String, userId: String): Boolean =
        File(getMarkovPath(chatId, userId)).delete()

    private fun deleteMessage(chatId: String, userId: String, text: String) {
        val path = getMarkovPath(chatId, userId)
        MarkovChain.read(path).let { markovChain ->
            markovChain.remove(text.split(whitespaceRegex))
            markovChain.write(path)
        }
    }

    private fun getMarkovPath(chatId: String, userId: String): String =
        Paths.get(getChatPath(chatId), "$userId.json").toString()

    private fun getChatPath(chatId: String): String =
        Paths.get(dataPath, chatId).toString().also { File(it).mkdirs() }

    private fun getUsernamesPath(): String {
        File(dataPath).mkdirs()
        return Paths.get(dataPath, "usernames.json").toString()
    }

    private fun matchesCommand(text: String, command: String): Boolean =
        text == "/$command" || text == "/$command@$myUsername"

    companion object {
        private const val YES = "yes"
        private val whitespaceRegex = Regex("\\s+")

        @JvmStatic
        fun main(args: Array<String>) = mainBody {
            val a = ArgParser(args).parseInto(::Args)
            val config = Config.read(a.configPath)
            Bot(config.telegramBotToken, a.dataPath).run()
        }
    }

    class Args(parser: ArgParser) {
        val configPath by parser.storing("-c", "--config", help = "Path to config YAML file")
        val dataPath by parser.storing("-d", "--data", help = "Path to data directory")
    }
}
