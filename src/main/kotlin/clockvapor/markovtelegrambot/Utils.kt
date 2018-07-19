package clockvapor.markovtelegrambot

import me.ivmg.telegram.Bot
import me.ivmg.telegram.entities.Chat
import me.ivmg.telegram.entities.ChatMember
import me.ivmg.telegram.entities.Message
import me.ivmg.telegram.entities.MessageEntity
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

fun log(s: String) {
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    println("$timestamp: $s")
}

fun log(t: Throwable) {
    log(t.localizedMessage)
    t.printStackTrace()
}

fun getMessageEntityText(message: Message, entity: MessageEntity): String =
    message.text!!.substring(entity.offset, entity.offset + entity.length)

fun MessageEntity.isMention(): Boolean =
    type == "mention" || type == "text_mention"

fun isAdmin(bot: Bot, chat: Chat, userId: Long): Boolean = if (chat.allMembersAreAdministrators == true) {
    true
} else {
    getChatMember(bot, chat.id, userId)?.let {
        it.status == "creator" || it.status == "administrator"
    } ?: false
}

fun getChatMember(bot: Bot, chatId: Long, userId: Long): ChatMember? {
    val (response, _) = bot.getChatMember(chatId, userId)
    return if (response != null && response.isSuccessful) {
        response.body()?.let { body ->
            if (body.ok) {
                body.result
            } else {
                null
            }
        }
    } else {
        null
    }
}

fun <T> tryOrNull(f: () -> T): T? = try {
    f()
} catch (e: Exception) {
    null
}
