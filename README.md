# markov-telegram-bot

`markov-telegram-bot` is a Telegram bot that builds a Markov chain for each user in groups it is added to, and it uses those Markov
chains to generate "new" messages from those users when prompted. The process is similar to your phone keyboard's predictive text
capabilities.

Markov chains are created per user, per group, so you don't have to worry about things you say in one group appearing in generated
messages in another group.

Since this bot stores the contents of every message sent in groups it is added to, it is advised that you create your own
unique Telegram bot and use this library to control it. I do have my own instance of the bot running, but, for privacy's sake, I
don't allow it to join any groups except a few close-knit ones. **Whoever owns some particular instance of this bot will be able
to see every word said in the bot's groups.**

## Sample Usage

### /msg
To generate a message from a user, use the `/msg` command. The command expects a user mention following it to know which user
you want to generate a message from: `/msg @some_user`. This works with a normal `@` mention and also a text mention for users
who don't have a username. Either way, just type the `@` character and select a user from the dropdown that opens.

You can also include an optional "seed" word following the user mention to tell the bot which word to start with when it generates
the message: `/msg @some_user blah`

### /deletemydata
The `/deletemydata` command allows you to delete your own Markov chain data for the current group. Simply send the command and
confirm your choice when the bot asks.

### /deletemessagedata
The `/deletemessagedata` command allows you to delete a specific message from your Markov chain data for the current group. As a
reply to the message you want to remove, send the command and confirm your choice when the bot asks.

### /deleteuserdata
The `/deleteuserdata` command allows group admins to delete Markov chain data for a specific user in the current group. If you are
an admin, simply send the command with a user mention following it, and confirm your choice when the bot asks:
`/deleteuserdata @some_user`. As with the `/msg` command, just type the `@` character and select a user from the dropdown that
opens.

## Running the Bot

The program expects two arguments:

1. Path to a config YAML file.
2. Path to a data directory. This is where all of the Markov chain data will be stored.

These arguments are given like so:

-c \<config yml path> -d \<data directory path>

The data directory doesn't need to exist ahead of time. The config YAML file does though, and it must contain the following
entry and nothing else:

    telegramBotToken: <your bot token>

That's it!
