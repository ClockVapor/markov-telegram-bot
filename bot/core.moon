lfs = require "lfs"
json = require "cjson"
{ :log, :join, :trim, :read_file, :write_file, :mkdir, :try, :pairslen } = require "bot.util"
local *

-- Used for start and end of messages.
-- i.e. data.words[empty_word]    = map of words that start messages,
--  and data.words[x][empty_word] = how many times a word ends a message
empty_word = ""

create_api = (config, start_time) ->
  pending_self_deletes = {}
  pending_user_deletes = {}
  pending_message_deletes = {}
  yes = "yes"
  local handle, reply

  api = require("telegram-bot-lua.core").configure config.token
  bot_id = do
    me = api.get_me!
    if not (me and me.ok)
      error "Failed to getMe() to get bot user information"
    me.result.id
  log "Bot ID = #{bot_id}"

  api.on_message = (message) ->
    if message.new_chat_members
      for user in *message.new_chat_members
        if user.id == bot_id
          log "Bot was added to chat #{message.chat.id}"
          get_chat_path message.chat.id -- create an empty directory for the new chat
          break
    if message.left_chat_member
      if message.left_chat_member.id == bot_id
        log "Bot was removed from chat #{message.chat.id}"
        delete_chat message.chat.id
      else
        delete_markov message.chat.id, message.left_chat_member.id
    elseif message.from
      if message.from.username and message.from.id
        store_username message.from.username, message.from.id
      if message.text
        handle message

  handle = (message) ->
    should_analyze = true

    if pending_self_deletes[message.from.id]
      should_analyze = false
      pending_self_deletes[message.from.id] = nil
      reply_text = if trim(message.text)\lower! == yes
        if delete_markov message.chat.id, message.from.id then "Okay. I deleted your data in this group."
        else "Hmm... I tried to delete your data, but it failed for some reason."
      else "Okay. I won't delete your data then."
      reply message, reply_text

    elseif pending_user_deletes[message.from.id]
      should_analyze = false
      user_id = pending_user_deletes[message.from.id]
      pending_user_deletes[message.from.id] = nil
      reply_text = if trim(message.text)\lower! == yes
        if delete_markov message.chat.id, user_id then "Okay. I deleted their data in this group."
        else "Hmm... I tried to delete their data, but it failed for some reason."
      else "Okay. I won't delete their data then."
      reply message, reply_text

    elseif pending_message_deletes[message.from.id]
      should_analyze = false
      msg = pending_message_deletes[message.from.id]
      pending_message_deletes[message.from.id] = nil
      reply_text = if trim(message.text)\lower! == yes
        remove msg
        "Okay. I deleted that message from your data in this group."
      else "Okay. I won't delete that message from your data then."
      reply message, reply_text

    elseif message.entities and #message.entities > 0
      e1 = message.entities[1]
      e1_text = message.text\sub e1.offset + 1, e1.offset + e1.length

      switch e1.type
        when "bot_command"
          should_analyze = false
          switch e1_text
            when "/msg", "/msg@#{config.bot_name}"
              if message.date and message.date >= start_time
                reply_text = if #message.entities > 1
                  e2 = message.entities[2]
                  user_id, e2_text = get_mention_user_id message, e2
                  user_id and (generate message.chat.id, user_id) or "<no data available for #{e2_text}>"
                else
                  "<expected a user mention>"
                reply message, reply_text

            when "/deletemydata", "/deletemydata@#{config.bot_name}"
              if message.date and message.date >= start_time
                pending_self_deletes[message.from.id] = true
                pending_user_deletes[message.from.id] = nil
                pending_message_deletes[message.from.id] = nil
                reply message, "Are you sure you want to delete your Markov chain data in this group? Say " ..
                  "\"yes\" to confirm, or anything else to cancel."

            when "/deleteuserdata", "/deleteuserdata@#{config.bot_name}"
              if message.date and message.date >= start_time
                admin = if message.chat.all_members_are_administrators
                  true
                else
                  result = api.get_chat_member message.chat.id, message.from.id
                  result and result.ok and
                    (result.result.status == "administrator" or result.result.status == "creator")
                reply_text = if admin
                  if #message.entities > 1
                    e2 = message.entities[2]
                    user_id, e2_text = get_mention_user_id message, e2
                    if user_id
                      pending_user_deletes[message.from.id] = user_id
                      pending_self_deletes[message.from.id] = nil
                      pending_message_deletes[message.from.id] = nil
                      "Are you sure you want to delete #{e2_text}'s Markov chain data in this group? Say \"yes\" to " ..
                        "confirm, or anything else to cancel."
                    else "I couldn't find that user."
                  else "You need to tell me which user's data to delete!"
                else "You aren't an administrator!"
                reply message, reply_text

            when "/deletemessagedata", "/deletemessagedata@#{config.bot_name}"
              if message.date and message.date >= start_time
                reply_to_message = message.reply_to_message
                reply_text = if reply_to_message
                  if reply_to_message.from and reply_to_message.from.id == message.from.id
                    pending_self_deletes[message.from.id] = nil
                    pending_user_deletes[message.from.id] = nil
                    pending_message_deletes[message.from.id] = reply_to_message
                    "Are you sure you want to delete that message from your Markov chain data? Say \"yes\" to " ..
                      "confirm, or anything else to cancel."
                  else "That isn't your message!"
                else "You need to reply to your message whose data you want to delete!"
                reply message, reply_text

    if should_analyze then analyze message

  reply = (message, text) ->
    api.send_message message.chat.id, text, nil, nil, nil, message.message_id

  api

-- Analyzes the given Telegram message and updates the Markov chain file for the sending user.
analyze = (message) ->
  data = read_markov(message.chat.id, message.from.id) or { words: {} }
  words = get_words message.text
  add_words_to_markov data.words, words
  write_markov message.chat.id, message.from.id, data

remove = (message) ->
  data = read_markov message.chat.id, message.from.id
  if data and data.words
    words = get_words message.text
    remove_words_from_markov data.words, words
    if pairslen(data.words) == 0
      delete_markov message.chat.id, message.from.id
    else
      write_markov message.chat.id, message.from.id, data

-- Generates a message for the given user in the given chat.
generate = (chat_id, user_id) ->
  data = read_markov chat_id, user_id
  if data and data.words
    words = {}
    last = data.words[empty_word]
    while last
      word = get_random_word last
      if word and word != empty_word
        table.insert words, word
        last = data.words[word]
      else
        last = nil
    join words, " "

-- Returns the user id associated with a "mention" message element, or nil if the element is of the wrong type,
-- and also returns the message element's text.
get_mention_user_id = (message, element) ->
  text = message.text\sub element.offset + 1, element.offset + element.length
  user_id = switch element.type
    when "mention" then get_user_id text\sub 2 -- remove the leading @
    when "text_mention" then element.user.id
  user_id, text

-- Reads the Markov chain file for the given user in the given chat, or returns nil if it isn't found.
read_markov = (chat_id, user_id) ->
  try -> json.decode read_file get_markov_path chat_id, user_id

-- Writes to the Markov chain file for the given user in the given chat.
write_markov = (chat_id, user_id, data) ->
  path = get_markov_path chat_id, user_id
  text = json.encode data
  write_file path, text

-- Deletes the Markov chain file for the given user in the given chat.
delete_markov = (chat_id, user_id) ->
  os.remove get_markov_path chat_id, user_id

-- Deletes all data associated with the given chat id.
delete_chat = (chat_id) ->
  chat_path = get_chat_path chat_id
  pcall ->
    for f in lfs.dir chat_path
      if f != "." and f != ".."
        res, e = os.remove "#{chat_path}/#{f}"
        if not res
          log "Failed to remove file #{f}: #{e}"
    res, e = os.remove chat_path
    if not res
      log "Failed to remove chat directory #{chat_path}: #{e}"

-- Adds a list of words to the given Markov chain.
add_words_to_markov = (map, words) ->
  len = #words
  if len > 0
    add_pair_to_markov map, empty_word, words[1]
    for i = 1, len - 1
      add_pair_to_markov map, words[i], words[i + 1]
    add_pair_to_markov map, words[len], empty_word

-- Removes a list of words from the given Markov chain.
remove_words_from_markov = (map, words) ->
  len = #words
  if len > 0
    remove_pair_from_markov map, empty_word, words[1]
    for i = 1, len - 1
      remove_pair_from_markov map, words[i], words[i + 1]
    remove_pair_from_markov map, words[len], empty_word

-- Adds one pair of (word, next_word) to the given Markov chain.
add_pair_to_markov = (map, word, next_word) ->
  map[word] or= {}
  if map[word][next_word] == nil
    map[word][next_word] = 1
  else
    map[word][next_word] += 1

-- Removes one pair of (word, next_word) from the given Markov chain.
remove_pair_from_markov = (map, word, next_word) ->
  if map[word] and map[word][next_word]
    map[word][next_word] -= 1
    if map[word][next_word] == 0
      map[word][next_word] = nil
      if pairslen(map[word]) == 0
        map[word] = nil

-- Reads the stored map of usernames to user ids, or returns nil if it cannot be read.
read_usernames = ->
  try -> json.decode read_file get_usernames_path!

-- Writes a map of usernames to user ids to the usernames file.
write_usernames = (map) ->
  write_file get_usernames_path!, json.encode map

-- Gets the stored user id associated with a username.
get_user_id = (username) ->
  map = read_usernames!
  if map then map[username\lower!]

-- Stores a username to user id mapping in the usernames file.
store_username = (username, user_id) ->
  map = read_usernames! or {}
  map[username\lower!] = tostring user_id
  write_usernames map

-- Splits a string into a list of words.
get_words = (s) ->
  [w for w in s\gmatch "%S+"]

-- Gets a (weighted) random word from the given word count map.
get_random_word = (word_count_map) ->
  total_count = 0
  for _, count in pairs word_count_map do total_count += count
  i = math.random(total_count) - 1 -- [0, total_count)
  current = 0
  for word, count in pairs word_count_map
    current += count
    if i < current
      return word
  
-- Creates the data directory and returns its path.
get_data_path = ->
  path = "data"
  mkdir path
  path

-- Creates the directory for a chat and returns its path.
get_chat_path = (chat_id) ->
  path = "#{get_data_path!}/#{chat_id}"
  mkdir path
  path

-- Creates the directory for the Markov chain file for the given user
-- in the given chat, and then returns the path to the file.
get_markov_path = (chat_id, user_id) ->
  "#{get_chat_path chat_id}/#{user_id}.json"

-- Creates the directory for the usernames file and then returns the path to the file.
get_usernames_path = ->
  "#{get_data_path!}/usernames.json"

{ :create_api, :get_words, :get_random_word, :add_words_to_markov, :add_pair_to_markov, :remove_words_from_markov,
  :remove_pair_from_markov }
