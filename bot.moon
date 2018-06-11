yaml = require "lyaml"
core = require "bot.core"
{ :log, :read_file } = require "bot.util"
local *

main = ->
  api = core.create_api load_config!, os.time!
  log "bot started"
  api.run 100, 5, 0, { "message" }

-- Loads config.yml as a table and verifies its contents.
load_config = ->
  config_file_path = "config.yml"
  local config
  pcall -> config = yaml.load read_file config_file_path
  if config == nil
    print "error: failed to load #{config_file_path}"
    os.exit 1
  for key in *{ "token", "bot_name" }
    if config[key] == nil
      print "error: missing \"#{key}\" entry in #{config_file_path}"
      os.exit 1
  config

main!
