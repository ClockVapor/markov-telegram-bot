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

Create a Telegram bot via @BotFather. Take down your bot's access token, and set its privacy mode to disabled so it can
read all messages in its groups. If privacy mode is enabled, the bot won't be able to build Markov chains. Then, using @BotFather's /setcommands command, copy and paste the following text as your input to set your bot's command list.

    msg - Generate message from a user
    deletemydata - Delete your Markov chain data in this group
    deletemessagedata - Delete a message from your Markov chain data in this group
    deleteuserdata - (Admin only) Delete a user's Markov chain data in this group
    
Now you will need to build some code. The projects all use [Maven](https://maven.apache.org/), so get that installed if you
haven't already. Download the latest source code for the [markov project](https://github.com/ClockVapor/markov) and also for
the [markov-telegram-bot project](https://github.com/ClockVapor/markov-telegram-bot). Unzip both, enter a command line in
the `markov` root directory, and run `mvn clean install`. Then enter the root `markov-telegram-bot` directory and run
`mvn clean package`. Two jars will be generated in the `target` directory; you need run the `jar-with-dependencies` one.

Create a folder wherever you want to store the bot's files. Copy the `jar-with-dependencies` into this folder, and create a YAML
file in there too with the following contents:

    telegramBotToken: <your bot token>
    
Replace `<your bot token>` with the token @BotFather gave you when you created your bot. I call this file `config.yml`, but you
can use any name you want.

Now you're ready to run the bot. Open a command line inside your bot folder and run the following command:

    java -jar <jar path> -c <config yml path> -d <data directory path>
    
Replace `<jar path>` with the name of the `jar-with-dependencies` file you copied into the folder, `<config yml path>` with the
name of the YAML file you created in the folder, and `<data directory path>` with whatever path you want to store the bot's
Markov chain data in (I just use `data`).

That's it!
