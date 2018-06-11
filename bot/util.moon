lfs = require "lfs"
local *

log = (msg) ->
  print "#{os.date("%x %X", os.time!)}: #{msg}"

-- Joins a table with a string separator.
join = (t, s) ->
  result = ""
  len = #t
  if len > 0
    for i = 1, len - 1
      result ..= "#{t[i]}#{s}"
    result ..= tostring t[len]
  result

-- Removes leading and trailing whitespace from a string.
trim = (s) ->
  s\gsub("^%s+", "")\gsub("%s+$", "")

-- Reads all text from a file.
read_file = (path) ->
  file = io.open path, "r"
  if file == nil then error "couldn't read file: #{path}"
  text = file\read "*a"
  io.close file
  text

-- Overwrites a file with the given text.
write_file = (path, text) ->
  file = io.open path, "w"
  if file == nil then error "couldn't write file: #{path}"
  text = file\write text
  io.close file

mkdir = (path) ->
  res = lfs.attributes path, "mode"
  if not res -- only continue if the file didn't exist
    res, e = lfs.mkdir path
    if not res
      log "Failed to create directory \"#{path}\": #{e}"

{ :log, :join, :trim, :read_file, :write_file, :mkdir }
