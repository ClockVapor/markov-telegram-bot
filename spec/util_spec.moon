{ :join, :trim, :pairslen } = require "bot.util"

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

describe "pairslen()", ->
  it "works", ->
    assert.same 1, pairslen { a: 1 }

  it "works", ->
    assert.same 3, pairslen { a: 1, b: 2, c: 3 }

  it "works on empty table", ->
    assert.same 0, pairslen {}

  it "works on array", ->
    assert.same 3, pairslen { 1, 2, 3 }

  it "works on array and table combo", ->
    assert.same 5, pairslen { 1, 2, 3, a: 1, b: 2 }
