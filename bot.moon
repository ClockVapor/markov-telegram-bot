yaml = require "lyaml"
json = require "lunajson"
lfs = require "lfs"
inspect = require "inspect"
{ :log, :join, :read_file, :write_file } = require "bot.util"
local *

main = ->
  start_time = os.time!
  config = load_config!
  api = require("telegram-bot-lua.core").configure config.token
  log "Bot started"

  api.on_message = (message) ->
    if message.from.username and message.from.id
      store_username message.from.username, message.from.id
    if message.text
      should_analyze = true
      if message.entities and #message.entities > 0
        e1 = message.entities[1]
        if e1.type == "bot_command" and e1.offset == 0
          cmd = message.text\sub e1.offset + 1, e1.offset + e1.length
          if cmd == "/msg" or cmd == "/msg@person_simulator_bot"
            should_analyze = false
            if message.date and message.date >= start_time
              response = if #message.entities > 1
                e2 = message.entities[2]
                e2_text = message.text\sub e2.offset + 1, e2.offset + e2.length
                user_id = switch e2.type
                  when "mention" then get_user_id e2_text\sub 2 -- remove the leading @
                  when "text_mention" then e2.user.id
                local m
                if user_id
                  log "(#{message.chat.id}, #{get_from_display_name message}) generating message for #{e2_text}"
                  m = generate message.chat.id, user_id
                if m == nil then m = "<no data available for #{e2_text}>"
                m
              else
                "<expected a user mention>"
              api.send_message message.chat.id, response, nil, nil, nil, message.message_id

      if should_analyze then analyze message

  api.run!

-- Loads config.yml as a table and verifies its contents.
load_config = ->
  config_file_path = "config.yml"
  config = nil
  pcall -> config = yaml.load read_file config_file_path
  if config == nil
    print "error: failed to load #{config_file_path}"
    os.exit(1)
  elseif config.token == nil
    print "error: missing \"token\" entry in #{config_file_path}"
    os.exit(1)
  config

-- Analyzes the given Telegram message and updates 
-- the applicable Markov chain file for the sending user.
analyze = (message) ->
  data = read_markov(message.chat.id, message.from.id) or { words: {} }
  words = get_words message.text
  log "(#{message.chat.id}, #{get_from_display_name message}): #{message.text}"
  add_words_to_markov data.words, words
  write_markov message.chat.id, message.from.id, data

-- Used for start and end of messages.
-- i.e. data.words[empty_word]    = map of words that start messages,
--  and data.words[x][empty_word] = how many times a word ends a message
empty_word = ""

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

-- Reads the Markov chain file for the given user in the given chat,
-- or returns nil if it isn't found.
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
  if map[word] == nil then map[word] = {}
  if map[word][next_word] == nil
    map[word][next_word] = 1
  else
    map[word][next_word] += 1

-- Stores a username to user id mapping in the usernames file.
store_username = (username, user_id) ->
  map = read_usernames! or {}
  map[username\lower!] = user_id
  write_usernames map

-- Gets the stored user id associated with a username.
get_user_id = (username) ->
  map = read_usernames!
  if map then map[username\lower!]

-- Reads the stored map of usernames to user ids, or returns nil if
-- it cannot be read.
read_usernames = ->
  local map
  pcall -> map = json.decode read_file get_usernames_path!
  map

-- Writes a map of usernames to user ids to the usernames file.
write_usernames = (map) ->
  write_file get_usernames_path!, json.encode map
  
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

-- Splits the given string into a list of words.
get_words = (s) ->
  [w for w in s\gmatch "%S+"]

-- Gets a weighted random word from the given map (which maps a word to how many times it occurred).
get_random_word = (follow_map) ->
  total_count = 0
  for _, count in pairs follow_map do total_count += count
  i = math.random(total_count) - 1 -- [0, total_count)
  current = 0
  for word, count in pairs follow_map
    current += count
    if i < current
      return word

-- Gets a display string for the sender of a message.
get_from_display_name = (message) ->
  if message.from.username
    message.from.username
  elseif message.from.first_name and message.from.last_name
    "#{message.from.first_name} #{message.from.last_name}"
  elseif message.from.first_name
    message.from.first_name
  else
    message.from.id

main!
