package clockvapor.telegram.markov

import clockvapor.markov.MarkovChain
import clockvapor.telegram.*
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

class MarkovTelegramBot(private val token: String, private val dataPath: String) {
    companion object {
        private const val YES = "yes"

        @JvmStatic
        fun main(args: Array<String>) = mainBody {
            val a = ArgParser(args).parseInto(MarkovTelegramBot::Args)
            val config = Config.read(a.configPath)
            MarkovTelegramBot(config.telegramBotToken, a.dataPath).run()
        }
    }

    private var myId: Long? = null
    private lateinit var myUsername: String
    private val wantToDeleteOwnData = mutableMapOf<String, MutableSet<String>>()
    private val wantToDeleteUserData = mutableMapOf<String, MutableMap<String, String>>()
    private val wantToDeleteMessageData = mutableMapOf<String, MutableMap<String, String>>()

    fun run() {
        val bot = bot {
            this.token = this@MarkovTelegramBot.token
            logLevel = HttpLoggingInterceptor.Level.NONE
            dispatch {
                addHandler(object : Handler({ bot, update -> handleUpdate(bot, update) }) {
                    override val groupIdentifier = "None"
                    override fun checkUpdate(update: Update) = update.message != null
                })
            }
        }
        val me = bot.getMe()
        val id = me.first?.body()?.result?.id
        val username = me.first?.body()?.result?.username
        if (id == null || username == null) {
            val exception = me.first?.errorBody()?.string()?.let(::Exception) ?: me.second ?: Exception("Unknown error")
            throw Exception("Failed to retrieve bot's username/id", exception)
        }
        myId = id
        myUsername = username
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

                matchesCommand(e0Text, "msgall") ->
                    doMessageTotalCommand(bot, message, chatId, text, entities)

                matchesCommand(e0Text, "stats") ->
                    doStatisticsCommand(bot, message, chatId, text)

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
            bot.sendChatAction(chatId.toLong(), ChatAction.TYPING)
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
            bot.sendChatAction(chatId.toLong(), ChatAction.TYPING)
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
            bot.sendChatAction(chatId.toLong(), ChatAction.TYPING)
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

        bot.sendChatAction(chatId.toLong(), ChatAction.TYPING)
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

    private fun doMessageTotalCommand(bot: Bot, message: Message, chatId: String, text: String,
                                      entities: List<MessageEntity>) {

        bot.sendChatAction(chatId.toLong(), ChatAction.TYPING)
        val e0 = entities[0]
        val remainingTexts = text.substring(e0.offset + e0.length).trim().takeIf { it.isNotBlank() }
            ?.split(whitespaceRegex).orEmpty()
        val replyText = when (remainingTexts.size) {
            0 -> generateMessageTotal(chatId)

            1 -> generateMessageTotal(chatId, remainingTexts.first())?.let { result ->
                when (result) {
                    is MarkovChain.GenerateWithSeedResult.NoSuchSeed ->
                        "<no such seed exists>"
                    is MarkovChain.GenerateWithSeedResult.Success ->
                        result.message.takeIf { it.isNotEmpty() }?.joinToString(" ")
                }
            }

            else -> "<expected only one seed word>"
        } ?: "<no data available>"
        reply(bot, message, replyText)
    }

    private fun doStatisticsCommand(bot: Bot, message: Message, chatId: String, text: String) {
        bot.sendChatAction(chatId.toLong(), ChatAction.TYPING)
        val markovPaths = getAllPersonalMarkovPaths(chatId)
        val userIdToWordCountsMap = markovPaths
            .mapNotNull { path ->
                tryOrNull { MarkovChain.read(path) }
                    ?.let { Pair(File(path).nameWithoutExtension, it.wordCounts) }
            }
            .toMap()
        val universe = computeUniverse(userIdToWordCountsMap.values)
        val listText = userIdToWordCountsMap.mapNotNull { (userId, wordCounts) ->
            val response = bot.getChatMember(chatId.toLong(), userId.toLong())
            val chatMember = response.first?.body()?.result
            if (chatMember != null) {
                val mostDistinguishingWords = scoreMostDistinguishingWords(wordCounts, universe).keys.take(5)
                "${chatMember.user.displayName.takeIf { it.isNotBlank() }
                    ?: chatMember.user.username?.takeIf { it.isNotBlank() }
                    ?: "User ID: $userId"}\n" +
                    mostDistinguishingWords.mapIndexed { i, word -> "${i + 1}. $word" }.joinToString("\n")
            } else null
        }.filter { it.isNotBlank() }.joinToString("\n\n")
        val replyText = if (listText.isBlank()) "<no data available>"
        else "Most distinguishing words:\n\n$listText"
        reply(bot, message, replyText)
    }

    private fun doDeleteMyDataCommand(bot: Bot, message: Message, chatId: String, senderId: String) {
        bot.sendChatAction(chatId.toLong(), ChatAction.TYPING)
        wantToDeleteOwnData.getOrPut(chatId) { mutableSetOf() } += senderId
        val replyText = "Are you sure you want to delete your Markov chain data in this group? " +
            "Say \"yes\" to confirm, or anything else to cancel."
        reply(bot, message, replyText)
    }

    private fun doDeleteUserDataCommand(bot: Bot, message: Message, chatId: String, from: User, senderId: String,
                                        entities: List<MessageEntity>) {

        bot.sendChatAction(chatId.toLong(), ChatAction.TYPING)
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
        bot.sendChatAction(chatId.toLong(), ChatAction.TYPING)
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
        val markovChain = tryOrNull(reportException = false) { MarkovChain.read(path) } ?: MarkovChain()
        val totalMarkovChain = getOrCreateTotalMarkovChain(chatId)
        val words = text.split(whitespaceRegex)
        markovChain.add(words)
        markovChain.write(path)
        totalMarkovChain.add(words)
        totalMarkovChain.write(getTotalMarkovPath(chatId))
    }

    private fun getOrCreateTotalMarkovChain(chatId: String): MarkovChain {
        val path = getTotalMarkovPath(chatId)
        var markovChain = tryOrNull(reportException = false) { MarkovChain.read(path) }
        if (markovChain == null) {
            markovChain = MarkovChain()
            for (personalMarkovChain in readAllPersonalMarkov(chatId)) {
                markovChain.add(personalMarkovChain)
            }
            markovChain.write(path)
        }
        return markovChain
    }

    private fun generateMessage(chatId: String, userId: String): String? =
        tryOrNull(reportException = false) { MarkovChain.read(getMarkovPath(chatId, userId)) }?.generate()
            ?.takeIf { it.isNotEmpty() }?.joinToString(" ")

    private fun generateMessage(chatId: String, userId: String,
                                seed: String): MarkovChain.GenerateWithSeedResult? =
        tryOrNull(reportException = false) { MarkovChain.read(getMarkovPath(chatId, userId)) }
            ?.generateWithCaseInsensitiveSeed(seed)

    private fun generateMessageTotal(chatId: String): String? =
        tryOrNull(reportException = false) { getOrCreateTotalMarkovChain(chatId) }?.generate()
            ?.takeIf { it.isNotEmpty() }?.joinToString(" ")

    private fun generateMessageTotal(chatId: String, seed: String): MarkovChain.GenerateWithSeedResult? =
        tryOrNull(reportException = false) { getOrCreateTotalMarkovChain(chatId) }
            ?.generateWithCaseInsensitiveSeed(seed)

    private fun reply(bot: Bot, message: Message, text: String, parseMode: ParseMode? = null) {
        bot.sendMessage(message.chat.id, text, replyToMessageId = message.messageId, parseMode = parseMode)
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
        tryOrNull(reportException = false) { readUsernames() }?.get(username.toLowerCase(Locale.ENGLISH))

    private fun storeUsername(username: String, userId: String) {
        val usernames = tryOrNull(reportException = false) { readUsernames() } ?: mutableMapOf()
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

    private fun deleteMarkov(chatId: String, userId: String): Boolean {
        // remove personal markov chain from total markov chain
        val path = getMarkovPath(chatId, userId)
        val markovChain = tryOrNull(reportException = false) { MarkovChain.read(path) } ?: MarkovChain()
        val totalMarkovChain = getOrCreateTotalMarkovChain(chatId)
        totalMarkovChain.remove(markovChain)
        totalMarkovChain.write(getTotalMarkovPath(chatId))

        // delete personal markov chain
        return File(path).delete()
    }

    private fun deleteMessage(chatId: String, userId: String, text: String) {
        val words = text.split(whitespaceRegex)

        // remove from personal markov chain
        val path = getMarkovPath(chatId, userId)
        val markovChain = tryOrNull(reportException = false) { MarkovChain.read(path) } ?: MarkovChain()
        markovChain.remove(words)
        markovChain.write(path)

        // remove from total markov chain
        val totalMarkovChain = getOrCreateTotalMarkovChain(chatId)
        totalMarkovChain.remove(words)
        totalMarkovChain.write(getTotalMarkovPath(chatId))
    }

    private fun readAllPersonalMarkov(chatId: String): List<MarkovChain> =
        getAllPersonalMarkovPaths(chatId)
            .map { MarkovChain.read(it) }

    private fun getAllPersonalMarkovPaths(chatId: String): List<String> =
        File(getChatPath(chatId)).listFiles()
            .filter { !it.name.endsWith("total.json") }
            .map { it.path }

    private fun getMarkovPath(chatId: String, userId: String): String =
        Paths.get(getChatPath(chatId), "$userId.json").toString()

    private fun getTotalMarkovPath(chatId: String): String =
        Paths.get(getChatPath(chatId), "total.json").toString()

    private fun getChatPath(chatId: String): String =
        Paths.get(dataPath, chatId).toString().also { File(it).mkdirs() }

    private fun getUsernamesPath(): String {
        File(dataPath).mkdirs()
        return Paths.get(dataPath, "usernames.json").toString()
    }

    private fun matchesCommand(text: String, command: String): Boolean =
        text == "/$command" || text == "/$command@$myUsername"

    class Args(parser: ArgParser) {
        val configPath by parser.storing("-c", "--config", help = "Path to config YAML file")
        val dataPath by parser.storing("-d", "--data", help = "Path to data directory")
    }
}
