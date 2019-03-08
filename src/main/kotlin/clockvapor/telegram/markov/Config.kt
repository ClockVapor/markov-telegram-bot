package clockvapor.telegram.markov

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.io.File

class Config {
    lateinit var telegramBotToken: String

    companion object {
        fun read(path: String): Config =
            ObjectMapper(YAMLFactory()).readValue<Config>(File(path), Config::class.java)
    }
}
