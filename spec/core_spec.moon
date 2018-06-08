{ :get_words, :get_random_word, :add_words_to_markov, :add_pair_to_markov } = require "bot.core"

describe "get_words()", ->
  it "works", ->
    assert.same { "foo", "bar" }, get_words "foo bar"

  it "retains capitalization", ->
    assert.same { "foo", "Bar" }, get_words "foo Bar"

  it "retains punctuation", ->
    assert.same { "foo,", "bar!" }, get_words "foo, bar!"

describe "get_random_word()", ->
  it "returns the word if the map only has one word", ->
    assert.same "foo", get_random_word { foo: 3 }

describe "add_words_to_markov()", ->
  it "works", ->
    expected = {
      [""]: { foo: 1 }
      foo: { bar: 1, baz: 1 }
      bar: { foo: 1 }
      baz: { [""]: 1 }
    }
    result = {}
    add_words_to_markov result, { "foo", "bar", "foo", "baz" }
    assert.same expected, result

describe "add_pair_to_markov()", ->
  it "adds new pairs", ->
    expected = { foo: { bar: 1 } }
    result = {}
    add_pair_to_markov result, "foo", "bar"
    assert.same expected, result

  it "increments existing pairs", ->
    expected = { foo: { bar: 2 } }
    result = { foo: { bar: 1 } }
    add_pair_to_markov result, "foo", "bar"
    assert.same expected, result
    
