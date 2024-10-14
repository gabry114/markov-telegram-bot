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
        private const val DEFAULT_MESSAGE_THRESHOLD = 2

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

    private var messageCount = 0
    private var messageThreshold = DEFAULT_MESSAGE_THRESHOLD

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

            // Increment message count and check if threshold is reached
            messageCount++
            if (messageCount >= messageThreshold) {
                generateAndSendMarkovMessage(bot, chatId)
                messageCount = 0 // Reset the count
            }
        }
    }

    private fun generateAndSendMarkovMessage(bot: Bot, chatId: String) {
        val generatedMessage = generateMessageTotal(chatId) ?: "No data available for generating a message."
        bot.sendMessage(chatId.toLong(), generatedMessage)
    }

    private fun setThreshold(newThreshold: Int) {
        messageThreshold = newThreshold
        log("Message threshold updated to $messageThreshold")
    }

    private fun doSetThresholdCommand(bot: Bot, message: Message, chatId: String, text: String) {
        val newThreshold = text.split(" ").getOrNull(1)?.toIntOrNull()
        if (newThreshold != null && newThreshold > 0) {
            setThreshold(newThreshold)
            reply(bot, message, "Threshold successfully set to $newThreshold")
        } else {
            reply(bot, message, "Invalid threshold value. Please provide a positive integer.")
        }
    }

    private fun handleMessage(bot: Bot, message: Message, chatId: String, from: User, senderId: String, text: String, entities: List<MessageEntity>): Boolean {
        var shouldAnalyzeMessage = true
        val e0 = entities[0]
        if (e0.type == "bot_command" && e0.offset == 0) {
            shouldAnalyzeMessage = false
            val e0Text = getMessageEntityText(message, e0)

            when {
                matchesCommand(e0Text, "setthreshold") -> 
                    doSetThresholdCommand(bot, message, chatId, text)

                matchesCommand(e0Text, "msg") -> 
                    doMessageCommand(bot, message, chatId, text, entities)

                // Other commands...
            }
        }

        return shouldAnalyzeMessage
    }
    
    // Other methods unchanged...
}
