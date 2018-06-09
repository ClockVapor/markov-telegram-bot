lfs = require "lfs"
json = require "cjson"
{ :join, :trim, :read_file, :write_file } = require "bot.util"
local *

-- Used for start and end of messages.
-- i.e. data.words[empty_word]    = map of words that start messages,
--  and data.words[x][empty_word] = how many times a word ends a message
empty_word = ""

create_api = (config, start_time) ->
  pending_self_deletes = {}
  yes = "yes"
  local reply

  api = require("telegram-bot-lua.core").configure config.token
  api.on_message = (message) ->
    if message.from
      if message.from.username and message.from.id
        store_username message.from.username, message.from.id

      if message.text
        should_analyze = true

        if pending_self_deletes[message.from.id]
          should_analyze = false
          pending_self_deletes[message.from.id] = nil
          reply_text = if trim(message.text)\lower! == yes
            if delete_markov message.chat.id, message.from.id
              "Okay. I deleted your data in this group."
            else
              "Hmm... I tried to delete your data, but it failed for some reason."
          else
            "Okay. I won't delete your data then."
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
                      e2_text = message.text\sub e2.offset + 1, e2.offset + e2.length
                      user_id = switch e2.type
                        when "mention" then get_user_id e2_text\sub 2 -- remove the leading @
                        when "text_mention" then e2.user.id
                      user_id and (generate message.chat.id, user_id) or "<no data available for #{e2_text}>"
                    else
                      "<expected a user mention>"
                    reply message, reply_text

                when "/deletemydata", "/deletemydata@#{config.bot_name}"
                  if message.date and message.date >= start_time
                    pending_self_deletes[message.from.id] = true
                    reply message, "Are you sure you want to delete your Markov chain data in this group? Say " ..
                      "\"yes\" to confirm, or anything else to cancel."

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

-- Reads the Markov chain file for the given user in the given chat, or returns nil if it isn't found.
read_markov = (chat_id, user_id) ->
  path = get_markov_path chat_id, user_id
  local data
  pcall -> data = json.decode read_file path
  data

-- Writes to the Markov chain file for the given user in the given chat.
write_markov = (chat_id, user_id, data) ->
  path = get_markov_path chat_id, user_id
  text = json.encode data
  write_file path, text

-- Deletes the Markov chain file for the given user in the given chat.
delete_markov = (chat_id, user_id) ->
  os.remove get_markov_path chat_id, user_id

-- Adds a list of words to the given Markov chain.
add_words_to_markov = (map, words) ->
  len = #words
  if len > 0
    add_pair_to_markov map, empty_word, words[1]
    for i = 1, len - 1
      add_pair_to_markov map, words[i], words[i + 1]
    add_pair_to_markov map, words[len], empty_word

-- Adds one pair of (word, next_word) to the given Markov chain.
add_pair_to_markov = (map, word, next_word) ->
  map[word] or= {}
  if map[word][next_word] == nil
    map[word][next_word] = 1
  else
    map[word][next_word] += 1

-- Reads the stored map of usernames to user ids, or returns nil if it cannot be read.
read_usernames = ->
  local map
  pcall -> map = json.decode read_file get_usernames_path!
  map

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
  lfs.mkdir path
  path

-- Creates the directory for a chat and returns its path.
get_chat_path = (chat_id) ->
  path = "#{get_data_path!}/#{chat_id}"
  lfs.mkdir path
  path

-- Creates the directory for the Markov chain file for the given user
-- in the given chat, and then returns the path to the file.
get_markov_path = (chat_id, user_id) ->
  "#{get_chat_path chat_id}/#{user_id}.json"

-- Creates the directory for the usernames file and then returns the path to the file.
get_usernames_path = ->
  "#{get_data_path!}/usernames.json"

{ :create_api, :get_words, :get_random_word, :add_words_to_markov, :add_pair_to_markov }
