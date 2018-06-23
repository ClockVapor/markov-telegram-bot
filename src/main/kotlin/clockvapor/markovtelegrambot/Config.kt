package clockvapor.markovtelegrambot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.io.File

class Config {
    lateinit var telegramBotName: String
    lateinit var telegramBotToken: String

    companion object {
        fun read(path: String): Config {
            val mapper = ObjectMapper(YAMLFactory())
            return mapper.readValue<Config>(File(path), Config::class.java)
        }
    }
}
