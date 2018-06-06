log = (msg) ->
  print os.date("%x %X", os.time!) .. ": " .. msg

join = (t, s) ->
  result = ""
  len = #t
  if len > 0
    for i = 1, len - 1
      result ..= tostring t[i] .. s
    result ..= tostring t[len]
  result

read_file = (path) ->
  file = io.open path, "r"
  if file == nil then error "couldn't read file: " .. path
  text = file\read "*a"
  io.close file
  text

write_file = (path, text) ->
  file = io.open path, "w"
  if file == nil then error "couldn't write file: " .. path
  text = file\write text
  io.close file

{ :log, :join, :read_file, :write_file }
