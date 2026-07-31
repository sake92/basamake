
- ignore .gitignore-d folders, hmm we need target/ for semanticdb files, but ignore .worktrees/ ..???
- poke should compile always.. say i open file, then only do "deder bsp install", currently it wont compile
- invalidation of wsIndex doesnt pick up newly-generated semantcdbs?
- move WorkspaceIndex to navigation/indexing
- cover getreferences in tests.. and fix it..

- keep last 10 (or ALL?) open-files parsed ASTs/semanticdbs, use hash/timestamp to avoid reparsing..

- cache deps/JDK indices GLOBALLY !!!
   - metadata.json (full_path, defines_packages:["java.lang"] for faster filtering/decision)
   - index.bin hashmap of symbol->url+range 

- improve nav experience:
    - index target sources as ASTs first, so user can navigate in open files (prioritize open files first!!)
    - after compile, parse semanticdb files, those override initial index entries
    - concurrently index JDK, and dependencies
    - store deps index in same place as target index
    - we need to get references in ALL targets from a dep source, e.g. pprint library or JDK

- improve BSP retries, after N-consecutive-fails make a pause 10s, then try on next user interaction?

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
