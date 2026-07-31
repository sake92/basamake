
- poke should compile always.. say i open file, then only do "deder bsp install", currently it wont compile
- invalidation of wsIndex doesnt pick up newly-generated semantcdbs?
- move WorkspaceIndex to navigation/indexing
- cover getreferences in tests.. and fix it..

- keep last 10 open parsed ASTs/semanticdbs, use hash/timestamp to avoid reparsign..

- cache deps/JDK indices GLOBALLY !!!
 - ~/.basamake/sources/jdk-21-hash/
   - metadata.json (full_path, defines_packages)
   - index.bin hashmap of symbol->url+range 
 - use https://github.com/lmdbjava/lmdbjava for indexes

- remove tupson in favor of upickle, no need for 2 json parsers..
- improve nav experience:
    - index target sources as ASTs first, so user can navigate in open files (prioritize open files first!!)
    - after compile, parse semanticdb files, those override initial index entries
    - concurrently index JDK, and dependencies
    - store deps index in same place as target index
    - we need to get references in ALL targets from a dep source, e.g. pprint library or JDK

- improve BSP retries, after N-consecutive-fails make a pause 10s, then try on next user interaction?

- optimize parsing deps sources, serialize index?
- readonly deps files..
- test mill
- dont bother with compile etc if editor not in focus? e.g. when ai coding agent touches many files?
- check thread safety
- parse javac -targetroot:${semanticdbDir}
- recursively parse scala/java source bodies, imports etc
- store deps index in same place as target index
    - we need to get references in ALL targets from a dep source, e.g. pprint library or JDK

New features:
- support hover info
- support for autocomplete
- support multi-root workspaces ? quite niche
