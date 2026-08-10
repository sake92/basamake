
- goto from 3.7 scala goes to 2.12 ..

22:40:39.372 [virtual-135] ERROR b.s.b.n.s.ScalaDefinitionsExtractor - Failed to parse Scala source '/home/sake/.cache/basamake/deps/org_scala-lang/scala-library_3.8.4_dddee0f0/src/scala/caps/package.scala': scala3: "`;` expected but `def` found"; scala2: "`;` expected but `,` found";
22:40:39.553 [virtual-135] ERROR b.s.b.n.s.ScalaDefinitionsExtractor - Failed to parse Scala source '/home/sake/.cache/basamake/deps/org_scala-lang/scala-library_3.8.4_dddee0f0/src/scala/collection/Map.scala': scala3: "`;` expected but `identifier` found"; scala2: "illegal start of definition `identifier`";
22:40:41.798 [virtual-135] ERROR b.s.b.n.s.ScalaDefinitionsExtractor - Failed to parse Scala source '/home/sake/.cache/basamake/deps/org_scala-lang/scala-library_3.8.4_dddee0f0/src/scala/util/Try.scala': scala3: "`}` expected but `[` found"; scala2: "`identifier` expected but `=>` found";


- pass in set of relevant source jars when looking for symbol, for BSP target


- write config with all BSP defaults, easier to override by user
- use paths relative to WORKSPACE, not absolute, easier to debug

- test mill
- check thread safety
- parse JAVAC -targetroot:${semanticdbDir}

New features:
- support hover info
- support for autocomplete
- support multi-root workspaces ? quite niche
