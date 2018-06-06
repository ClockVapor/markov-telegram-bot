yaml = require "lyaml"
json = require "lunajson"
lfs = require "lfs"
inspect = require "inspect"
{ :join, :read_file, :write_file } = require "bot.util"
local *

main = ->
  start_time = os.time!
  config = load_config!
  api = require("telegram-bot-lua.core").configure(config.token)
  print "bot started"

  api.on_message = (message) ->
    if message.text
      match = message.text\match "^/msg%s+(%w+)"
      if match and message.date and message.date >= start_time
        text = generate message.chat.id, match
        if text == nil then text = "<failed to generate message for " .. username .. ">"
        api.send_message message.chat.id, text
      else
        analyze message

  api.run!

load_config = ->
  config_file_path = "config.yml"
  config = yaml.load read_file config_file_path
  if config.token == nil
    print "error: missing \"token\" entry in " .. config_file_path
    os.exit(1)
  config

analyze = (message) ->
  if message.from.username
    data = read_markov message.chat.id, message.from.username
    words = get_words message.text
    if data.words == nil then data.words = {}
    print inspect words
    add_to_markov data.words, words
    write_markov message.chat.id, message.from.username, data

-- used for start and end of messages
empty_word = ""

generate = (chat_id, username) ->
  data = read_markov chat_id, username
  if data.words
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

read_markov = (chat_id, username) ->
  username = username\lower!
  path = get_markov_path chat_id, username
  data = nil
  pcall -> data = json.decode read_file path
  if data == nil
    data = {}
  data

write_markov = (chat_id, username, data) ->
  username = username\lower!
  path = get_markov_path chat_id, username
  text = json.encode data
  write_file path, text
  
get_markov_path = (chat_id, username) ->
  data_dir = "data"
  lfs.mkdir data_dir
  chat_dir = data_dir .. "/" .. tostring chat_id
  lfs.mkdir chat_dir
  chat_dir .. "/" .. username .. ".json"

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

get_words = (s) ->
  [w for w in s\gmatch "%S+"]

get_random_word = (map) ->
  total_count = 0
  for _, count in pairs map do total_count += count
  i = math.random(total_count) - 1
  current = 0
  for word, count in pairs map
    current += count
    if i < current
      return word

main!
