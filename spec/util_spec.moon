{ :join, :trim } = require "bot.util"

describe "trim()", ->
  it "works", ->
    assert.same "foo", trim "\t foo  \t\n "

  it "works on only whitespace", ->
    assert.same "", trim "     \t  "

describe "join()", ->
  it "works", ->
    assert.same "1, 2, 3", join { 1, 2, 3 }, ", "

  it "works on empty table", ->
    assert.same "", join {}, ", "
