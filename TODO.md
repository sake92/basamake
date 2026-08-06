
- concurrent indexing with VTs
- write config with all BSP defaults, easier to override by user
- use paths relative to WORKSPACE, not absolute, easier to debug

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
