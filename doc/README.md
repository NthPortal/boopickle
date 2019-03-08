# BooPickle

[![Join the chat at https://gitter.im/suzaku-io/boopickle](https://badges.gitter.im/suzaku-io/boopickle.svg)](https://gitter.im/suzaku-io/boopickle?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
[![Build Status](https://travis-ci.org/suzaku-io/boopickle.svg?branch=master)](https://travis-ci.org/suzaku-io/boopickle)
[![Scala.js](https://www.scala-js.org/assets/badges/scalajs-0.6.13.svg)](http://www.scala-js.org)

BooPickle is the [fastest](http://ochrons.github.io/boopickle-perftest/) and most size efficient serialization (aka pickling) library that works on Scala,
[Scala.js](http://www.scala-js.org) and [Scala Native](http://www.scala-native.org). It encodes into a binary format instead of the more customary JSON. A binary format brings efficiency 
gains in both size and speed, at the cost of legibility of the encoded data. BooPickle borrows heavily from both [uPickle](https://github.com/lihaoyi/upickle-pprint)
and [Prickle](https://github.com/benhutchison/prickle) so special thanks to Li Haoyi and Ben Hutchison for those two great libraries!

## Features

- Supports Scala, Scala.js and Scala Native (no reflection!)
- Serialization support for all primitives, collections, options, tuples and case classes (including class hierarchies)
- User-definable custom serializers
- Transforming serializers to simplify serializing non-case classes
- Handles references and deduplication of identical objects
- Very fast
- Very efficient coding
- Low memory usage, no intermediate structures needed
- Zero dependencies
- Scala 2.11/2.12
- All modern browsers are supported (not IE9 and below, though)

## What is it good for?

BooPickle is not a very generic serialization library, so you should think carefully before using it in your application. Typical good and
bad use cases are listed below.

 Good           | Bad 
----------------|-----------------------------------------
Mobile client/server communication | Public API for your service
Data transfer over Websocket binary protocol | Data storage (you will lose it if something changes!)
Scala <-> Scala communication | Scala <-> some-other-language communication
Clients with limited resources | Communication between server components
