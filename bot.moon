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
  print "bot started"

  api.on_message = (message) ->
    if message.text
      match = message.text\match "^/msg%s+(%w+)"
      if match and message.date and message.date >= start_time
        log "(#{message.chat.id}, #{get_from_display_name message}) generating message for #{match}"
        text = generate message.chat.id, match
        if text == nil then text = "<failed to generate message for #{match}>"
        api.send_message message.chat.id, text
      else
        analyze message

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
  data = read_markov message.chat.id, message.from.id
  if data == nil then data = { words: {} }
  words = get_words message.text
  log "(#{message.chat.id}, #{get_from_display_name message}): #{message.text}"
  add_to_markov data.words, words
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
  data = nil
  pcall -> data = json.decode read_file path
  data

-- Writes to the Markov chain file for the given user in the given chat.
write_markov = (chat_id, user_id, data) ->
  path = get_markov_path chat_id, user_id
  text = json.encode data
  write_file path, text
  
-- Creates the directories necessary for the Markov chain file for the given user
-- in the given chat, and then returns the path to the file.
get_markov_path = (chat_id, user_id) ->
  data_dir = "data"
  lfs.mkdir data_dir
  chat_dir = "#{data_dir}/#{chat_id}"
  lfs.mkdir chat_dir
  "#{chat_dir}/#{user_id}.json"

add_to_markov = (map, words) ->
  len = #words
  if len > 0
    if map[empty_word] == nil then map[empty_word] = {}
    first = words[1]
    if map[empty_word][first] == nil
      map[empty_word][first] = 1
    else
      map[empty_word][first] += 1

    for i = 1, len - 1
      w = words[i]
      nw = words[i + 1]
      if map[w] == nil then map[w] = {}
      if map[w][nw] == nil
        map[w][nw] = 1
      else
        map[w][nw] += 1

    last = words[len]
    if map[last] == nil then map[last] = {}
    if map[last][empty_word] == nil
      map[last][empty_word] = 1
    else
      map[last][empty_word] += 1

-- Splits the given string into a list of words.
get_words = (s) ->
  [w for w in s\gmatch "%S+"]

-- Gets a weighted random word from the given map (which maps a word to how many times it occurred).
get_random_word = (follow_map) ->
  total_count = 0
  for _, count in pairs follow_map do total_count += count
  i = math.random(total_count) - 1 -- [0, total_count-1]
  current = 0
  for word, count in pairs follow_map
    current += count
    if i < current
      return word

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
