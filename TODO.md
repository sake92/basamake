
- cleanup workspaceIndex.onDidChange(path, text) no need to pass text, we can read file
- write config with all BSP defaults, easier to override by user
- use paths relative to WORKSPACE, not absolute, easier to debug

- ignore .gitignore-d folders, hmm we need target/ for semanticdb files, but ignore .worktrees/ ..???
- poke should compile always.. say i open file, then only do "deder bsp install", currently it wont compile
- invalidation of wsIndex doesnt pick up newly-generated semantcdbs?
- cover getreferences in tests.. and fix it..

- keep last 10 (or ALL?) open-files parsed ASTs/semanticdbs, use hash/timestamp to avoid reparsing..

- cache deps/JDK indices GLOBALLY !!!
   - metadata.json (full_path, defines_packages:["java.lang"] for faster filtering/decision)
   - index.bin hashmap of symbol->url+range 

- concurrently index JDK, and dependencies
- store deps index in same place as target index
- we need to get references in ALL targets from a dep source, e.g. pprint library or JDK

- test mill
- check thread safety
- parse JAVAC -targetroot:${semanticdbDir}

New features:
- support hover info
- support for autocomplete
- support multi-root workspaces ? quite niche
